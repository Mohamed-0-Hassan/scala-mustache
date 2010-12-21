import scala.annotation.tailrec
import scala.io.Source
import scala.collection.MapLike
import java.lang.reflect.{Field=>F,Method=>M}

package mustache {

  case class MustacheParseException(line:Int, msg:String) 
    extends Exception("Line "+line+": "+msg)

  object Mustache {

    def apply(
        str:String
      ):Mustache = apply(Source.fromString(str))

    def apply(
        source:Source
      , open:String = "{{"
      , close:String = "}}"
      ):Mustache = new Mustache { 
            val compiledTemplate = 
              (new Parser{
                val src = source
                var otag = open
                var ctag = close
              }).parse 
          }

    private class ParserState
    private object Text extends ParserState
    private object OTag extends ParserState
    private object Tag  extends ParserState
    private object CTag extends ParserState

    private abstract class Parser {
      val src:Source

      var state:ParserState = Text
      var otag:String
      var ctag:String
      var tagPosition:Int = 0
      var line:Int = 1
      var prev:Char = '\uffff'
      var cur:Char = '\uffff'
      var curlyBraceTag:Boolean = false
      var stack:List[Token] = List()

      val buf = new StringBuilder(8192)

      def parse():Token = {

        while(consume) {
          state match {
            case Text =>
              if (cur == otag.charAt(0))
                if (otag.length > 1) { tagPosition = 1; state = OTag } 
                else { staticText; state = Tag }
              else buf.append(cur)

            case OTag =>
              if(cur == otag.charAt(tagPosition))
                if (tagPosition == otag.length-1) { staticText; state = Tag }
                else { tagPosition = tagPosition+1 }
              else { notOTag; buf.append(cur) }

            case Tag =>
              if (buf.isEmpty && cur == '{') {
                curlyBraceTag = true
                buf.append(cur)
              } else if (curlyBraceTag && cur == '}') {
                curlyBraceTag = false
                buf.append(cur)
              } else if (cur == ctag.charAt(0)) {
                if (ctag.length > 1) { tagPosition = 1; state = CTag }
                else tag
              } else buf.append(cur)

            case CTag =>
              if (cur == ctag.charAt(tagPosition)) {
                if (tagPosition == ctag.length-1) tag
                else { tagPosition = tagPosition+1 }
              } else { notCTag; buf.append(cur) }
        }
      }
      state match {
        case Text => staticText
        case OTag => { notOTag; staticText }
        case Tag => fail("Unclosed tag \""+buf.toString+"\"")
        case CTag => { notCTag; staticText }
      }
      stack.foreach {
        case IncompleteSection(key, inverted) => fail("Unclosed mustache section \""+key+"\"")
        case _ =>
      }
      val result = stack.reverse

      if(result.size == 1) result(0)
      else RootToken(result)
    }
    private def fail[A](msg:String):A = throw MustacheParseException(line,msg)

    private def consume = {
      prev = cur
      
      if (src.hasNext) {
        cur = src.next
        // \n, \r\n, \r
        if ( cur == '\r' ||
             ( cur == '\n' && prev != '\r' ) 
           ) line = line+1
        true
      } else false
    }

    private def notOTag = { 
      buf.append(otag.substring(0,tagPosition))
      state = Text 
    }
    private def notCTag = { 
      buf.append(ctag.substring(0,tagPosition))
      state = Tag
    }
    private def reduce:String = { val r = buf.toString; buf.clear; r }

    private def staticText:Unit = { 
      val r = reduce
      if (r.length>0) stack = StaticTextToken(r)::stack
    }

    private def checkContent(content:String):String = {
      val trimmed = content.trim
      if (trimmed.length == 0) fail("Empty tag")
      else trimmed
    }

    private def tag:Unit = {
      state = Text
      val content = checkContent(reduce)
      def skipFirst = checkContent(content substring 1)
      def skipBoth = checkContent(content substring(1, content.length-1))

      content.charAt(0) match {
        case '!' => // ignore comments
        case '&' =>
          stack = UnescapedToken(skipFirst)::stack
        case '{' =>
          if (content endsWith "}")
            stack = UnescapedToken(skipBoth)::stack
          else fail("Unbalanced \"{\" in tag \""+content+"\"")
        case '^' =>
          stack = IncompleteSection(skipFirst, true)::stack
        case '#' =>
          stack = IncompleteSection(skipFirst, false)::stack
        case '/' => {
          val name = skipFirst

          @tailrec
          def addSection(
            children:List[Token]
            ,s:List[Token]
          ) : List[Token] = s.headOption match {
            case None => fail("Closing unopened section \""+name+"\"")

            case Some(IncompleteSection(key, inverted)) 
              if (key == name) => SectionToken(inverted, name, children, otag, ctag)::s.tail

            case Some(IncompleteSection(key, inverted)) 
              if (key != name) => fail("Unclosed section \""+key+"\"")
  
            case Some(other) => 
              addSection(other::children, s.tail)
          }
          stack = addSection(List[Token](), stack)
        }
        case '>' | '<' =>
          stack = PartialToken(skipFirst)::stack
        case '=' =>
          if (content.size>2 && content.endsWith("=")) {
            val changeDelimiter = skipBoth
            changeDelimiter.split("""\s+""",-1).toSeq match {
              case Seq(o,c) => { otag = o; ctag = c }
              case _ => fail("Invalid change delimiter tag content: \""+changeDelimiter+"\"")
            }
          } else 
              fail("Invalid change delimiter tag content: \""+content+"\"")
        case _ => stack = EscapedToken(content)::stack
      }
    }
  }
  }

  /**
   * compiled template
   **/
  trait Mustache {
    protected val compiledTemplate:Token

    def render(
      context : Any = null
      , partials : Map[String,Mustache] = Map()
    ) : String = product(context, partials).toString

    def product(
      context : Any = null
      , partials : Map[String,Mustache] = Map()
    ) : TokenProduct = compiledTemplate.render(context, partials)
  }

  // mustache tokens ------------------------------------------
  trait TokenProduct {
    val maxLength:Int
    def write(out:StringBuilder):Unit

    override def toString = {
      val b = new StringBuilder(maxLength)
      write(b)
      b.toString
    }
  }

  object EmptyProduct extends TokenProduct {
    val maxLength = 0 
    def write(out:StringBuilder):Unit = {}
  }

  trait Token {
    def render(context:Any, partials:Map[String,Mustache]):TokenProduct
  }

  trait CompositeToken {
    def composite(tokens:Seq[Token],context:Any, partials:Map[String,Mustache]):TokenProduct = 
      composite(tokens.map{(_,context)},partials)

    def composite(tasks:Seq[Tuple2[Token,Any]], partials:Map[String,Mustache]):TokenProduct = {
      val result = tasks.map(t=>{t._1.render(t._2, partials)})
      val len = result.foldLeft(0)({_+_.maxLength})
      new TokenProduct {
        val maxLength = len
        def write(out:StringBuilder) = result.map{_.write(out)}
      }
    }
  }

  case class RootToken(children:List[Token]) extends Token with CompositeToken {
    def render(context:Any, partials:Map[String,Mustache]):TokenProduct =
      composite(children, context, partials)
  }

  case class IncompleteSection(key:String, inverted:Boolean) extends Token {
    def render(context:Any, partials:Map[String,Mustache]):TokenProduct =
      throw new Exception("Weird thing happened. There is incoplete section in compiled template.")
  }

  case class StaticTextToken(staticText:String) extends Token {
    private val product = 
      new TokenProduct {
        val maxLength = staticText.length
        def write(out:StringBuilder) = out.append(staticText)
      }
    def render(context:Any, partials:Map[String,Mustache]):TokenProduct = product
  }

  case class PartialToken(key:String) extends Token {
    def render(context:Any, partials:Map[String,Mustache]):TokenProduct =
      partials.get(key) match {
        case Some(template) => template.product(context, partials)
        case _ => throw new IllegalArgumentException("Partial \""+key+"\" is not defined.")
      }
  }

  trait ContextHandler {

    def valueOf(key:String, context:Any):Any =
      eval(fromContext(key, context))

    @tailrec
    private def eval(value:Any):Any =
      value match {
        case f:Function0[_] => eval(f())
        case other => other
      }

    private def fromContext(key:String, context:Any):Any =
      context match {
        case null => None
        case m : MapLike[String,_,_] =>
          m.get(key) match {
            case Some(v) => v
            case None => None
          }
        case any => reflection(any, key) 
      }

    private def reflection(x:Any, key:String):Any = {
      val w = wrapped(x)
      (methods(w).get(key), fields(w).get(key)) match {
        case (Some(m), _) => m.invoke(w)
        case (None, Some(f)) => f.get(w)
        case _ => None
      }
    }

    private def fields(w:AnyRef) = Map( 
      w.getClass().getFields.map(x => {x.getName -> x}):_*
    )

    private def methods(w:AnyRef) = Map(
      w.getClass().getMethods
        .filter(x => { x.getParameterTypes.length == 0 })
        .map(x => { x.getName -> x }) :_*
    )

    private def wrapped(x:Any):AnyRef =
      x match {
        case x: Byte => byte2Byte(x)
        case x: Short => short2Short(x)
        case x: Char => char2Character(x)
        case x: Int => int2Integer(x)
        case x: Long => long2Long(x)
        case x: Float => float2Float(x)
        case x: Double => double2Double(x)
        case x: Boolean => boolean2Boolean(x)
        case _ => x.asInstanceOf[AnyRef]
      }
  }

  trait ValuesFormatter {
    @tailrec
    final def format(value:Any):String =
      value match {
        case null => ""
        case None => ""
        case Some(v) => format(v)
        case x => x.toString
      }
  }

  case class SectionToken(
     inverted:Boolean
    ,key:String
    ,children:List[Token]
    ,otag:String
    ,ctag:String
  ) extends Token with ContextHandler with CompositeToken {
    val childrenString = children.map(_.toString).mkString

    def render(context:Any, partials:Map[String,Mustache]):TokenProduct =
      valueOf(key, context) match {
        case null => 
          if (inverted) composite(children, context, partials)
          else EmptyProduct
        case None => 
          if (inverted) composite(children, context, partials)
          else EmptyProduct
        case b:Boolean => 
          if (b^inverted) composite(children, context, partials)
          else EmptyProduct
        case s:Seq[_] if(inverted) => 
          if (s.isEmpty) composite(children, context, partials)
          else EmptyProduct
        case s:Seq[_] if(!inverted) => {
          val tasks = for (element<-s;token<-children) yield (token, element)
          composite(tasks,partials)
        }
        case m:MapLike[_,_,_] => // because Map[String,_] can be cast to Function[String,_]
          composite(children, m, partials)

        case fn:Function1[String,_] => {
          println("fn is "+fn)
          new TokenProduct {
            val maxLength = (childrenString.length*1.2).toInt // this is just an estimate
            def write(out:StringBuilder) = out.append(fn(childrenString))
          }
        } 
        case other => 
          composite(children, other, partials)
      }
  }

  case class UnescapedToken(key:String) 
    extends Token 
    with ContextHandler 
    with ValuesFormatter {

    def render(context:Any, partials:Map[String,Mustache]):TokenProduct = {
      val v = format(valueOf(key,context))
      new TokenProduct {
        val maxLength = v.length
        def write(out:StringBuilder):Unit = {out.append(v)}
      }
    }
  }

  case class EscapedToken(key:String) 
    extends Token 
    with ContextHandler 
    with ValuesFormatter {

    def render(context:Any, partials:Map[String,Mustache]):TokenProduct = { 
      val v = format(valueOf(key,context))
      new TokenProduct {
        val maxLength = (v.length*1.2).toInt
        def write(out:StringBuilder):Unit =
          v.foreach {
            case '<' => out.append("&lt;")
            case '>' => out.append("&gt;")
            case '&' => out.append("&amp;")
            case c => out.append(c)
          }
      }
    }
  }

}
