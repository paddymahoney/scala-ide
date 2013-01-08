package scala.tools.eclipse
package pc

import scala.tools.eclipse.javaelements.ScalaCompilationUnit
import scala.tools.eclipse.javaelements.ScalaSourceFile
import scala.tools.eclipse.testsetup.SDTTestUtils
import scala.tools.eclipse.logging.Logger
import scala.tools.nsc.interactive.InteractiveReporter
import org.eclipse.jdt.core.ICompilationUnit
import org.junit.Assert._
import org.junit._
import org.mockito.Matchers._
import org.mockito.Mockito._
import scala.tools.eclipse.hyperlink.HyperlinkTester
import scala.tools.eclipse.testsetup.CustomAssertion
import scala.tools.eclipse.testsetup.TestProjectSetup

object PresentationCompilerTest extends testsetup.TestProjectSetup("pc") with CustomAssertion with HyperlinkTester

class PresentationCompilerTest {
  import PresentationCompilerTest._

  @Test
  def creatingOverrideIndicator_ShouldNotReportError_t1000531() {
    // when
    val unit = open("t1000531/A.scala")
    val mockLogger = mock(classOf[Logger])

    // then
    project.withSourceFile(unit) { (sourceFile, compiler) =>
      try {
        compiler.withStructure(sourceFile, keepLoaded = true) { tree =>
          compiler.askOption { () =>
            val overrideIndicatorBuilder = new compiler.OverrideIndicatorBuilderTraverser(unit, new java.util.HashMap) {
              override val eclipseLog = mockLogger
            }
            // if the unit is not kept loaded (i.e., `keepLoaded = false`), then a message 
            // "Error creating override indicators" is reported. That is why this test checks
            // that no error is reported to the mocked logger.
            overrideIndicatorBuilder.traverse(tree)
          }
        }
      }
    }()

    // verify
    verify(mockLogger, times(0)).error(any())
  }

  @Test
  def implicitConversionFromPackageObjectShouldBeInScope_t1000647() {
    //when
    open("t1000647/foo/package.scala")

    // then
    val dataFlowUnit = open("t1000647/bar/DataFlow.scala")
    
    // give a chance to the background compiler to report the error
    waitUntilTypechecked(dataFlowUnit)

    // verify
    assertNoErrors(dataFlowUnit)
  }

  @Test
  def illegalCyclicReferenceInvolvingObject_t1000658() {
    //when
    val unit = scalaCompilationUnit("t1000658/ThreadPoolConfig.scala")
    //then
    reload(unit)
    // verify
    assertNoErrors(unit)
  }
  
  @Ignore("Ticket #1000692 is fixed (at least it looks like it is working). However this test it is still failing. "+
      "We decided to look at it and understand why it is not passing only after 2.0 release.")
  @Test
  def notEnoughArgumentsForCconstructorError_ShouldNotBeReported_t1000692() {
    //when
    val unit = scalaCompilationUnit("t1000692/akka/util/ReflectiveAccess.scala")
    val oracle = List(Link("class t1000692.akka.config.ModuleNotAvailableException"))
    //then
    // it is important to ask hyperlinking before reloading!
    loadTestUnit(unit).andCheckAgainst(oracle) 
    reload(unit)
    // verify
    assertNoErrors(unit)
  }
  
  @Test
  def psShouldReportTheCorrectCompilationUnitsItKnowsAbout() {
    def managedUnits() = project.withPresentationCompiler(_.compilationUnits)()
    
    project.shutDownCompilers()
    
    // should be empty
    Assert.assertTrue("Presentation compiler should not maintain any units after a shutdown request", managedUnits().isEmpty)
    
    val cu = scalaCompilationUnit("t1000692/akka/util/ReflectiveAccess.scala")
    
    // still no units should be loaded
    Assert.assertTrue("Presentation compiler should not maintain any units after structure build (%s)".format(managedUnits()), managedUnits().isEmpty)
    
    cu.scheduleReconcile().get

    // now the unit should be managed
    Assert.assertEquals("Presentation compiler should maintain one unit after reload (%s)".format(managedUnits()), 1, managedUnits().size)
  }
  
  @Test
  @Ignore("Enable this test once #1000976 is fixed")
  def correctlyTypecheckClassesWithDefaultArguments_t1000976() {
    def openUnitAndTypecheck(path: String): ScalaSourceFile = {
      val unit = scalaCompilationUnit(path).asInstanceOf[ScalaSourceFile]
      unit.reload()
      waitUntilTypechecked(unit)
      unit
    }

    // SUT: Opening A.scala w/ full typecheck and then B.scala determines the "ghost error" to show up 
    openUnitAndTypecheck("t1000976/a/A.scala")
    val unitB = openUnitAndTypecheck("t1000976/b/B.scala")

    // verify
    assertNoErrors(unitB)
  }
  
  
  @Test
  def uniqueParseTree_t1001326() {

    val cu = scalaCompilationUnit("t1001326/ParsedTree.scala")
    val newTree = project.withSourceFile(cu) { (sf, compiler) =>
      val parseTree1 = compiler.parseTree(sf)
      val parseTree2 = compiler.parseTree(sf)
      parseTree1 != parseTree2
    } (false)

    Assert.assertTrue("Asking twice for a parseTree on the same source should always return a new tree", newTree)
  }

  @Test
  def unattributedParseTree_t1001326() {
    val cu = scalaCompilationUnit("t1001326/ParsedTree.scala")
    val noSymbols = project.withSourceFile(cu) { (sf, c) =>
      noSymbolsOrTypes(new CompilerAndTree { 
        val compiler = c
        val tree = compiler.parseTree(sf)
      })
    } (false)
    Assert.assertTrue("A parseTree should never contain any symbols or types", noSymbols)
  }

  @Test
  def neverModifyParseTree_t1001326() {
    val path = "t1001326/ParsedTree.scala"
    val cu = scalaCompilationUnit(path)

    val notChanged = project.withSourceFile(cu) { (sf, c) =>
      val compilerAndTree = new CompilerAndTree { 
        val compiler = c
        val tree = compiler.parseTree(sf)
      }
      open(path)
      noSymbolsOrTypes(compilerAndTree)
    } (false)

    Assert.assertTrue("Once you have obtained a parseTree it should never change", notChanged)

  }

  // We don't have dependent method types in Scala 2.9.x so this is a work-around.
  private trait CompilerAndTree {
    val compiler: ScalaPresentationCompiler
    val tree: compiler.Tree
  }

  /**
   * Traverses a tree and makes sure that there are no types or symbols present in the tree with
   * the exception of the symbol for the package 'scala'. This is because that symbol will be
   * present in some of the nodes that the compiler generates.
   */
  private def noSymbolsOrTypes(compilerandTree: CompilerAndTree): Boolean = {
    import compilerandTree.compiler._
    val existsSymbolsOrTypes = compilerandTree.tree.exists { t =>
      t.symbol != null &&
      t.symbol != NoSymbol &&
      t.symbol != definitions.ScalaPackage && // ignore the symbol for the scala package for now
      t.tpe != null &&
      t.tpe != NoType
    }
    !existsSymbolsOrTypes
  }

}