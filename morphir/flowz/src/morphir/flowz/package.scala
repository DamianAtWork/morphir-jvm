package morphir

import morphir.flowz.instrumentation.InstrumentationLogging
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.prelude._

import scala.collection.immutable.SortedSet

package object flowz {
  type Properties    = Has[Properties.Service]
  type Annotated[+A] = (A, PropertyMap)

  object CommandLineArgs extends Subtype[List[String]]
  type CommandLineArgs = CommandLineArgs.Type

  object Variables extends Subtype[Map[String, String]]
  type Variables = Variables.Type

  type FlowArgs = Has[FlowArguments]

  type StepUidGenerator = uidGenerator.UidGenerator
  type StepRuntimeEnv   = InstrumentationLogging with StepUidGenerator with Clock
  type FlowInitEnv      = FlowArgs with InstrumentationLogging with Clock with Console

//  type ForkedStep[-StateIn, +StateOut, -Env, -Params, +Err, +Output] =
//    Act[StateIn, Unit, Env, Params, Nothing, Fiber.Runtime[Err, StepOutputs[StateOut, Output]]]

  type ZBehavior[-SIn, +SOut, -InputMsg, -Env, +E, +A] = ZIO[(SIn, InputMsg, Env), E, StepSuccess[SOut, A]]

  type Activity[-SIn, +SOut, -Msg, -Env, +E, +A] = ZIO[SIn with Msg with Env, E, StepSuccess[SOut, A]]

  type StatelessStep[-InputMsg, -R, +E, +A] = Step[Any, Any, InputMsg, R, E, A]

  type ZIOStep[-R, +E, +A] = Step[Any, Any, Any, R, E, A]

  /**
   * A type alias for a step that acts like an impure function, taking in an input message
   * (also referred to as input/parameters) and produces a single value, possibly failing
   * with a `Throwable`.
   *
   * For example:
   *
   * {{{
   *   val intConverter:FuncStep[String,Int] =
   *    Step.fromFunction { numberStr:String => numberStr.toInt }
   * }}}
   */
  type FuncStep[-InputMsg, +A] = Step[Any, Any, InputMsg, Any, Throwable, A]

  /**
   * Provides a description of an independent behavior which does not
   * rely on any inputs to produce its outputs.
   */
  type IndieStep[+S, +E, +A] = Step[Any, S, Any, Any, E, A]

  type StepExecutionId = uidGenerator.Uid

  def step[SIn, SOut, Msg, R, Err, A](
    label: String
  )(theStep: Step[SIn, SOut, Msg, R, Err, A]): RunnableStep[SIn, SOut, Msg, R with StepRuntimeEnv, Err, A] =
    RunnableStep.step(label)(theStep)

  /**
   * The `Properties` trait provides access to a property map that flows and behaviors
   * can add arbitrary properties to. Each property consists of a string
   * identifier, an initial value, and a function for combining two values.
   * Properties form monoids and you can think of `Properties` as a more
   * structured logging service or as a super polymorphic version of the writer
   * monad effect.
   */
  object Properties {

    trait Service extends Serializable {
      def addProperty[V](key: Property[V], value: V): UIO[Unit]
      def get[V](key: Property[V]): UIO[V]
      def withAnnotation[R, E, A](zio: ZIO[R, E, A]): ZIO[R, Annotated[E], Annotated[A]]
      def supervisedFibers: UIO[SortedSet[Fiber.Runtime[Any, Any]]]
    }

    /**
     * Accesses a `Properties` instance in the environment and appends the
     * specified property to the property map.
     */
    def addProperty[V](key: Property[V], value: V): URIO[Properties, Unit] =
      ZIO.accessM(_.get.addProperty(key, value))

    /**
     * Accesses a `Properties` instance in the environment and retrieves the
     * property of the specified type, or its default value if there is none.
     */
    def get[V](key: Property[V]): URIO[Properties, V] =
      ZIO.accessM(_.get.get(key))

    /**
     * Constructs a new `Properties` service.
     */
    val live: Layer[Nothing, Properties] =
      ZLayer.fromEffect(FiberRef.make(PropertyMap.empty).map { fiberRef =>
        new Properties.Service {
          def addProperty[V](key: Property[V], value: V): UIO[Unit] =
            fiberRef.update(_.annotate(key, value))
          def get[V](key: Property[V]): UIO[V] =
            fiberRef.get.map(_.get(key))
          def withAnnotation[R, E, A](zio: ZIO[R, E, A]): ZIO[R, Annotated[E], Annotated[A]] =
            fiberRef.locally(PropertyMap.empty) {
              zio.foldM(e => fiberRef.get.map((e, _)).flip, a => fiberRef.get.map((a, _)))
            }
          def supervisedFibers: UIO[SortedSet[Fiber.Runtime[Any, Any]]] =
            ZIO.descriptorWith { descriptor =>
              get(Property.fibers).flatMap {
                case Left(_) =>
                  val emptySet = SortedSet.empty[Fiber.Runtime[Any, Any]]
                  ZIO.succeed(emptySet)
                case Right(refs) =>
                  ZIO
                    .foreach(refs)(_.get)
                    .map(_.foldLeft(SortedSet.empty[Fiber.Runtime[Any, Any]])(_ ++ _))
                    .map(_.filter(_.id != descriptor.id))
              }
            }
        }
      })

    /**
     * Accesses an `Properties` instance in the environment and executes the
     * specified effect with an empty annotation map, returning the annotation
     * map along with the result of execution.
     */
    def withAnnotation[R <: Properties, E, A](zio: ZIO[R, E, A]): ZIO[R, Annotated[E], Annotated[A]] =
      ZIO.accessM(_.get.withAnnotation(zio))

    /**
     * Returns a set of all fibers in this test.
     */
    def supervisedFibers: ZIO[Properties, Nothing, SortedSet[Fiber.Runtime[Any, Any]]] =
      ZIO.accessM(_.get.supervisedFibers)
  }

  /**
   * Aspect syntax allows you to apply an aspect to your `Step`.
   */
  implicit final class AspectSyntax[-SIn, +SOut, -P, -R, +E, +A](private val step: Step[SIn, SOut, P, R, E, A]) {

    /**
     * Syntax for adding aspects.
     */
    def @@[SIn1 <: SIn, SOut1 >: SOut, P1 <: P, R1 <: R, E1 >: E, A1 >: A](
      aspect: StepAspect[SIn1, SOut1, P1, R1, E1, A1]
    ): Step[SIn1, SOut1, P1, R1, E1, A1] = aspect(step)
  }
}
