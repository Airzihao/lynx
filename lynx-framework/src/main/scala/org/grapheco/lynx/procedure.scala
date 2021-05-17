package org.grapheco.lynx

import com.typesafe.scalalogging.LazyLogging
import org.grapheco.lynx.func.{LynxProcedure, LynxProcedureArgument}
import org.grapheco.lynx.util.LynxDateUtil
import org.opencypher.v9_0.expressions.{Expression, FunctionInvocation}
import org.opencypher.v9_0.frontend.phases.CompilationPhaseTracer.CompilationPhase
import org.opencypher.v9_0.frontend.phases.CompilationPhaseTracer.CompilationPhase.AST_REWRITE
import org.opencypher.v9_0.frontend.phases.{BaseContext, BaseState, Condition, Phase}
import org.opencypher.v9_0.util.{InputPosition, Rewriter, bottomUp, inSequence}

import scala.collection.mutable
import org.opencypher.v9_0.util.symbols.CTAny

trait CallableProcedure {
  val inputs: Seq[(String, LynxType)]
  val outputs: Seq[(String, LynxType)]

  def call(args: Seq[LynxValue]): LynxValue

  def signature(procedureName: String) = s"$procedureName(${inputs.map(x => Seq(x._1, x._2).mkString(":")).mkString(",")})"

  def checkArguments(procedureName: String, argTypesActual: Seq[LynxType]) = {
    if (argTypesActual.size != inputs.size)
      throw WrongNumberOfArgumentsException(s"$procedureName(${inputs.map(x => Seq(x._1, x._2).mkString(":")).mkString(",")})",
        inputs.size, argTypesActual.size)

    inputs.zip(argTypesActual).foreach(x => {
      if (x._1._2 != x._2)
        throw WrongArgumentException(x._1._1, x._2, x._1._2)
    })
  }
}

trait ProcedureRegistry {
  def getProcedure(prefix: List[String], name: String, argsLength: Int): Option[CallableProcedure]
}

class DefaultProcedureRegistry(types: TypeSystem, classes: Class[_]*) extends ProcedureRegistry with LazyLogging {
  val procedures = mutable.Map[(String, Int), CallableProcedure]()

  classes.foreach(registerAnnotatedClass(_))

  def registerAnnotatedClass(clazz: Class[_]): Unit = {
    val host = clazz.newInstance()
    clazz.getDeclaredMethods.foreach(met => {
      val an = met.getAnnotation(classOf[LynxProcedure])
      //yes, you are a LynxFunction
      if (an != null) {
        //input arguments
        val inputs = met.getParameters.map(par => {
          val pan = par.getAnnotation(classOf[LynxProcedureArgument])
          val argName =
            if (pan == null) {
              par.getName
            }
            else {
              pan.name()
            }

          argName -> types.typeOf(par.getType)
        })

        //TODO: N-tuples
        val outputs = Seq("value" -> types.typeOf(met.getReturnType))
        register(an.name(), inputs, outputs, (args) => types.wrap(met.invoke(host, args: _*)))
      }
    })
  }

  def register(name: String, argsLength: Int, procedure: CallableProcedure): Unit = {
    procedures((name, argsLength)) = procedure
    logger.debug(s"registered procedure: ${procedure.signature(name)}")
  }

  def register(name: String, inputs0: Seq[(String, LynxType)], outputs0: Seq[(String, LynxType)], call0: (Seq[LynxValue]) => LynxValue): Unit = {
    register(name, inputs0.size, new CallableProcedure() {
      override val inputs: Seq[(String, LynxType)] = inputs0
      override val outputs: Seq[(String, LynxType)] = outputs0
      override def call(args: Seq[LynxValue]): LynxValue = LynxValue(call0(args))
    })
  }

  override def getProcedure(prefix: List[String], name: String, argsLength: Int): Option[CallableProcedure] = procedures.get(((prefix :+ name).mkString("."), argsLength))
}

case class UnknownProcedureException(prefix: List[String], name: String) extends LynxException {
  override def getMessage: String = s"unknown procedure: ${(prefix :+ name).mkString(".")}"
}

case class WrongNumberOfArgumentsException(signature: String, sizeExpected: Int, sizeActual: Int) extends LynxException {
  override def getMessage: String = s"Wrong number of arguments of $signature(), expected: $sizeExpected, actual: $sizeActual"
}

case class WrongArgumentException(argName: String, expectedType: LynxType, actualType: LynxType) extends LynxException {
  override def getMessage: String = s"Wrong argument of $argName, expected: $expectedType, actual: ${actualType}"
}

case class ProcedureExpression(val funcInov: FunctionInvocation)(implicit runnerContext: CypherRunnerContext) extends Expression {
  val procedure: CallableProcedure = runnerContext.procedureRegistry.getProcedure(funcInov.namespace.parts, funcInov.functionName.name, funcInov.args.size).get
  val args: Seq[Expression] = funcInov.args
  val aggregating: Boolean = funcInov.containsAggregate

  override def position: InputPosition = funcInov.position

  override def productElement(n: Int): Any = funcInov.productElement(n)

  override def productArity: Int = funcInov.productArity

  override def canEqual(that: Any): Boolean = funcInov.canEqual(that)

  override def containsAggregate: Boolean = funcInov.containsAggregate

  override def findAggregate: Option[Expression] = funcInov.findAggregate

}

case class FunctionMapper(runnerContext: CypherRunnerContext) extends Phase[BaseContext, BaseState, BaseState] {
  override def phase: CompilationPhase = AST_REWRITE

  override def description: String = "map functions to their procedure implementations"

  override def process(from: BaseState, ignored: BaseContext): BaseState = {
    val rewriter = inSequence(
      bottomUp(Rewriter.lift {
        case func: FunctionInvocation => ProcedureExpression(func)(runnerContext)
      }))
    val newStatement = from.statement().endoRewrite(rewriter)
    from.withStatement(newStatement)
  }

  override def postConditions: Set[Condition] = Set.empty
}

class DefaultProcedures {
  @LynxProcedure(name = "lynx")
  def lynx(): String = {
    "lynx-0.3"
  }
  @LynxProcedure(name = "sum")
  def sum(inputs: LynxList): LynxValue = {
    LynxValue(inputs.value.map(_.asInstanceOf[LynxNumber].number.doubleValue()).sum)
  }

  @LynxProcedure(name = "max")
  def max(inputs: LynxList): LynxValue = {
    LynxValue(inputs.value.map(_.asInstanceOf[LynxNumber].number.doubleValue()).max)
  }

  @LynxProcedure(name = "min")
  def min(inputs: LynxList): LynxValue = {
    LynxValue(inputs.value.map(_.asInstanceOf[LynxNumber].number.doubleValue()).min)
  }

  @LynxProcedure(name = "power")
  def power(x: LynxNumber, y: LynxNumber): LynxValue = {
    LynxValue(math.pow(x.number.doubleValue(), y.number.doubleValue()))
  }

  @LynxProcedure(name="date")
  def date(inputs: LynxString): LynxDate = {
    if (inputs == null) LynxDateUtil.now()
    LynxDateUtil.parse(inputs.value)
  }

  @LynxProcedure(name="date")
  def date(): LynxDate = {
    LynxDateUtil.now()
  }

  @LynxProcedure(name= "abs")
  def abs(x: LynxNumber): LynxValue = {
    LynxValue(math.abs(x.number.doubleValue()))
  }

  @LynxProcedure(name= "ceil")
  def ceil(x: LynxNumber): LynxValue = {
    LynxValue(math.ceil(x.number.doubleValue()))
  }

  @LynxProcedure(name= "floor")
  def floor(x: LynxNumber): LynxValue = {
   LynxValue(math.floor(x.number.doubleValue()))
  }

  @LynxProcedure(name= "rand")
  def rand(): LynxValue = {
    LynxValue(math.random())
  }

  @LynxProcedure(name= "round")
  def round(x: LynxNumber): LynxValue = {
    LynxValue(math.round(x.number.doubleValue()))
  }

  @LynxProcedure(name= "round")
  def round(x: LynxNumber, precision: LynxInteger): LynxValue = {
    val base = math.pow(10, precision.value)
    LynxValue(math.round(base * x.number.doubleValue()).toDouble / base)
  }

  @LynxProcedure(name= "sign")
  def sign(x: LynxNumber): LynxValue = {
   LynxValue(math.signum(x.number.doubleValue()))
  }

  @LynxProcedure(name= "e")
  def e(): LynxValue = {
   LynxValue(Math.E)
  }

  @LynxProcedure(name= "exp")
  def exp(x: LynxNumber): LynxValue = {
    LynxValue(math.exp(x.number.doubleValue()))
  }

  @LynxProcedure(name= "log")
  def log(x: LynxNumber): LynxValue = {
   LynxValue(math.log(x.number.doubleValue()))
  }

  @LynxProcedure(name= "log10")
  def log10(x: LynxNumber): LynxValue = {
    LynxValue(math.log10(x.number.doubleValue()))
  }

  @LynxProcedure(name= "sqrt")
  def sqrt(x: LynxNumber): LynxValue = {
   LynxValue(math.sqrt(x.number.doubleValue()))
  }

  @LynxProcedure(name= "acos")
  def acos(x: LynxNumber): LynxValue = {
   LynxValue(math.acos(x.number.doubleValue()))
  }

  @LynxProcedure(name= "asin")
  def asin(x: LynxNumber): LynxValue = {
    LynxValue(math.asin(x.number.doubleValue()))
  }

  @LynxProcedure(name= "atan")
  def atan(x: LynxNumber): LynxValue = {
   LynxValue(math.atan(x.number.doubleValue()))
  }

  @LynxProcedure(name= "atan2")
  def atan2(x: LynxNumber, y: LynxNumber): LynxValue = {
    LynxValue(math.atan2(x.number.doubleValue(), y.number.doubleValue()))
  }

  @LynxProcedure(name= "cos")
  def cos(x: LynxNumber): LynxValue = {
   LynxValue(math.cos(x.number.doubleValue()))
  }

  @LynxProcedure(name= "cot")
  def cot(x: LynxNumber): LynxValue = {
    LynxValue(1.0 / math.tan(x.number.doubleValue()))
  }

  @LynxProcedure(name= "degrees")
  def degrees(x: LynxNumber): LynxValue = {
    LynxValue(math.toDegrees(x.number.doubleValue()))
  }

  @LynxProcedure(name= "haversin")
  def haversin(x: LynxNumber): LynxValue = {
    LynxValue((1.0d - math.cos(x.number.doubleValue())) / 2)
  }

  @LynxProcedure(name= "pi")
  def pi(): LynxValue = {
   LynxValue(Math.PI)
  }

  @LynxProcedure(name= "radians")
  def radians(x: LynxNumber): LynxValue = {
    LynxValue(math.toRadians(x.number.doubleValue()))
  }

  @LynxProcedure(name= "sin")
  def sin(x: LynxNumber): LynxValue = {
    LynxValue(math.sin(x.number.doubleValue()))
  }

  @LynxProcedure(name= "tan")
  def tan(x: LynxNumber): LynxValue = {
   LynxValue(math.tan(x.number.doubleValue()))
  }

}