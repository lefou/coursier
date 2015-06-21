package coursier
package web

import coursier.core.{Resolver, Logger, Remote}
import japgolly.scalajs.react.vdom.{TagMod, Attr}
import japgolly.scalajs.react.vdom.Attrs.dangerouslySetInnerHtml
import japgolly.scalajs.react.{ReactEventI, ReactComponentB, BackendScope}
import japgolly.scalajs.react.vdom.prefix_<^._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import org.scalajs.jquery.jQuery

import scala.concurrent.Future

import scala.scalajs.js
import js.Dynamic.{global => g}

case class ResolutionOptions(followOptional: Boolean = false,
                             keepTest: Boolean = false)

case class State(modules: Seq[Dependency],
                 repositories: Seq[Remote],
                 options: ResolutionOptions,
                 resolutionOpt: Option[Resolution],
                 editModuleIdx: Int,
                 resolving: Boolean,
                 reverseTree: Boolean,
                 log: Seq[String])

class Backend($: BackendScope[Unit, State]) {
  def updateDepGraph(resolution: Resolution) = {
    println("Rendering canvas")

    val graph = js.Dynamic.newInstance(g.Graph)()

    var nodes = Set.empty[String]
    def addNode(name: String) =
      if (!nodes(name)) {
        graph.addNode(name)
        nodes += name
      }

    for {
      (dep, parents) <- resolution.reverseDependencies.toList
      from = s"${dep.module.organization}:${dep.module.name}:${dep.scope.name}"
      _ = addNode(from)
      parDep <- parents
      to = s"${parDep.module.organization}:${parDep.module.name}:${parDep.scope.name}"
      _ = addNode(to)
    } {
      graph.addEdge(from, to)
    }

    val layouter = js.Dynamic.newInstance(g.Graph.Layout.Spring)(graph)
    layouter.layout()

    val width = jQuery("#dependencies").width()
    val height = math.max(jQuery("#dependencies").height().asInstanceOf[Int], 400)

    println(s"width: $width, height: $height")

    jQuery("#depgraphcanvas").html("") //empty()

    val renderer = js.Dynamic.newInstance(g.Graph.Renderer.Raphael)("depgraphcanvas", graph, width, height)
    renderer.draw()
    println("Rendered canvas")
  }

  def updateDepGraphBtn(resolution: Resolution)(e: ReactEventI) = {
    updateDepGraph(resolution)
  }

  def updateTree(resolution: Resolution, target: String, reverse: Boolean) = {
    def depsOf(dep: Dependency) =
      resolution.projectsCache.get(dep.moduleVersion).toSeq.flatMap(t => Resolver.finalDependencies(dep, t._2).filter(resolution.filter getOrElse Resolver.defaultFilter))

    lazy val reverseDeps = {
      var m = Map.empty[Module, Seq[Dependency]]

      for {
        dep <- resolution.dependencies
        trDep <- depsOf(dep)
      } {
        m += trDep.module -> (m.getOrElse(trDep.module, Nil) :+ dep)
      }

      m
    }

    def tree(dep: Dependency): js.Dictionary[js.Any] =
      js.Dictionary(Seq(
        "text" -> (s"${dep.module}": js.Any)
      ) ++ {
        val deps = if (reverse) reverseDeps.getOrElse(dep.module, Nil) else depsOf(dep)
        if (deps.isEmpty) Seq()
        else Seq("nodes" -> js.Array(deps.map(tree): _*))
      }: _*)

    println(resolution.dependencies.toList.map(tree).map(js.JSON.stringify(_)))
    g.$(target).treeview(js.Dictionary("data" -> js.Array(resolution.dependencies.toList.map(tree): _*)))
  }

  def resolve(action: => Unit = ()) = {
    g.$("#resLogTab a:last").tab("show")
    $.modState(_.copy(resolving = true, log = Nil))

    val logger: Logger = new Logger {
      def fetched(url: String) = {
        println(s"Fetched $url")
        $.modState(s => s.copy(log = s"Fetched $url" +: s.log))
      }
      def fetching(url: String) = {
        println(s"Fetching $url")
        $.modState(s => s.copy(log = s"Fetching $url" +: s.log))
      }
      def other(url: String, msg: String) = {
        println(s"$url: $msg")
        $.modState(s => s.copy(log = s"$url: $msg" +: s.log))
      }
    }

    val s = $.state
    def task = coursier.resolve(
      s.modules.toSet,
      fetchFrom(s.repositories.map(_.copy(logger = Some(logger)))),
      filter = Some(dep => (s.options.followOptional || !dep.optional) && (s.options.keepTest || dep.scope != Scope.Test))
    )

    // For reasons that are unclear to me, not delaying this when using the runNow execution context
    // somehow discards the $.modState above. (Not a major problem as queue is used by default.)
    Future(task)(scala.scalajs.concurrent.JSExecutionContext.Implicits.queue).flatMap(_.runF).foreach { res: Resolution =>
      $.modState{ s => updateDepGraph(res); updateTree(res, "#deptree", reverse = s.reverseTree); s.copy(resolutionOpt = Some(res), resolving = false)}
      g.$("#resResTab a:last").tab("show")
    }
  }
  def handleResolve(e: ReactEventI) = {
    println(s"Resolving")
    e.preventDefault()
    jQuery("#results").css("display", "block")
    resolve()
  }

  def clearLog(e: ReactEventI) = {
    $.modState(_.copy(log = Nil))
  }

  def toggleReverseTree(e: ReactEventI) = {
    $.modState{ s =>
      for (res <- s.resolutionOpt)
        updateTree(res, "#deptree", reverse = !s.reverseTree)
      s.copy(reverseTree = !s.reverseTree)
    }
  }

  def editModule(idx: Int)(e: ReactEventI) = {
    e.preventDefault()
    $.modState(_.copy(editModuleIdx = idx))
  }

  def removeModule(idx: Int)(e: ReactEventI) = {
    e.preventDefault()
    $.modState(s => s.copy(modules = s.modules.zipWithIndex.filter(_._2 != idx).map(_._1)))
  }

  def updateModule(moduleIdx: Int, update: (Dependency, String) => Dependency)(e: ReactEventI) = {
    if (moduleIdx >= 0) {
      $.modState{ state =>
        val dep = state.modules(moduleIdx)
        state.copy(modules = state.modules.updated(moduleIdx, update(dep, e.target.value)))
      }
    }
  }

  def addModule(e: ReactEventI) = {
    e.preventDefault()
    $.modState{ state =>
      val modules = state.modules :+ Dependency(Module("", ""), "")
      println(s"Modules:\n${modules.mkString("\n")}")
      state.copy(modules = modules, editModuleIdx = modules.length - 1)
    }
  }

  def enablePopover(e: ReactEventI) = {
    g.$("[data-toggle='popover']").popover()
  }

  object options {
    def toggleOptional(e: ReactEventI) = {
      $.modState(s => s.copy(options = s.options.copy(followOptional = !s.options.followOptional)))
    }
    def toggleTest(e: ReactEventI) = {
      $.modState(s => s.copy(options = s.options.copy(keepTest = !s.options.keepTest)))
    }
  }
}

object App {

  lazy val arbor = g.arbor

  val resultDependencies = ReactComponentB[(Resolution, Backend)]("Result")
    .render{ T =>
      val (res, backend) = T

      def infoLabel(label: String) =
        <.span(^.`class` := "label label-info", label)
      def errorPopOver(label: String, desc: String) =
        popOver("danger", label, desc)
      def infoPopOver(label: String, desc: String) =
        popOver("info", label, desc)
      def popOver(`type`: String, label: String, desc: String) =
        <.button(^.`type` := "button", ^.`class` := s"btn btn-xs btn-${`type`}",
          Attr("data-trigger") := "focus",
          Attr("data-toggle") := "popover", Attr("data-placement") := "bottom",
          Attr("data-content") := desc,
          ^.onClick ==> backend.enablePopover,
          ^.onMouseOver ==> backend.enablePopover,
          label
        )

      def depItem(dep: Dependency) =
        <.tr(
          ^.`class` := (if (res.errors.contains(dep.moduleVersion)) "danger" else ""),
          <.td(dep.module.organization),
          <.td(dep.module.name),
          <.td(dep.version),
          <.td(Seq[Seq[TagMod]](
            if (dep.scope == Scope.Compile) Seq() else Seq(infoLabel(dep.scope.name)),
            if (dep.`type`.isEmpty || dep.`type` == "jar") Seq() else Seq(infoLabel(dep.`type`)),
            if (dep.classifier.isEmpty) Seq() else Seq(infoLabel(dep.classifier)),
            Some(dep.exclusions).filter(_.nonEmpty).map(excls => infoPopOver("Exclusions", excls.toList.sorted.map{case (org, name) => s"$org:$name"}.mkString("; "))).toSeq,
            if (dep.optional) Seq(infoLabel("optional")) else Seq(),
            res.errors.get(dep.moduleVersion).map(errs => errorPopOver("Error", errs.mkString("; "))).toSeq
          )),
         <.td(Seq[Seq[TagMod]](
           res.projectsCache.get(dep.moduleVersion) match {
             case Some((repo: Remote, _)) =>
               // FIXME Maven specific, generalize if/when adding support for Ivy
               val relPath =
                 dep.module.organization.split('.').toSeq ++ Seq(
                   dep.module.name,
                   dep.version,
                   s"${dep.module.name}-${dep.version}"
                 )

               Seq(
                 <.a(^.href := s"${repo.base}${relPath.mkString("/")}.pom",
                   <.span(^.`class` := "label label-info", "POM")
                 ),
                 <.a(^.href := s"${repo.base}${relPath.mkString("/")}.jar",
                   <.span(^.`class` := "label label-info", "JAR")
                 )
               )

             case _ => Seq()
           }
         ))
        )

      val sortedDeps = res.dependencies.toList
        .sortBy(dep => coursier.core.Module.unapply(dep.module).get)

      <.table(^.`class` := "table",
        <.thead(
          <.tr(
            <.th("Organization"),
            <.th("Name"),
            <.th("Version"),
            <.th("Extra"),
            <.th("Links")
          )
        ),
        <.tbody(
          sortedDeps.map(depItem)
        )
      )
    }
    .build

  object icon {
    def apply(id: String) = <.span(^.`class` := s"glyphicon glyphicon-$id", ^.aria.hidden := "true")
    def ok = apply("ok")
    def edit = apply("pencil")
    def remove = apply("remove")
  }

  val moduleEditModal = ReactComponentB[((Module, String), Int, Backend)]("EditModule")
    .render{ P =>
      val ((module, version), moduleIdx, backend) = P
      <.div(^.`class` := "modal fade", ^.id := "moduleEdit", ^.role := "dialog", ^.aria.labelledby := "moduleEditTitle",
        <.div(^.`class` := "modal-dialog", <.div(^.`class` := "modal-content",
          <.div(^.`class` := "modal-header",
            <.button(^.`type` := "button", ^.`class` := "close", Attr("data-dismiss") := "modal", ^.aria.label := "Close",
              <.span(^.aria.hidden := "true", dangerouslySetInnerHtml("&times;"))
            ),
            <.h4(^.`class` := "modal-title", ^.id := "moduleEditTitle", "Dependency")
          ),
          <.div(^.`class` := "modal-body",
            <.form(
              <.div(^.`class` := "form-group",
                <.label(^.`for` := "inputOrganization", "Organization"),
                <.input(^.`class` := "form-control", ^.id := "inputOrganization", ^.placeholder := "Organization",
                  ^.onChange ==> backend.updateModule(moduleIdx, (dep, value) => dep.copy(module = dep.module.copy(organization = value))),
                  ^.value := module.organization
                )
              ),
              <.div(^.`class` := "form-group",
                <.label(^.`for` := "inputName", "Name"),
                <.input(^.`class` := "form-control", ^.id := "inputName", ^.placeholder := "Name",
                  ^.onChange ==> backend.updateModule(moduleIdx, (dep, value) => dep.copy(module = dep.module.copy(name = value))),
                  ^.value := module.name
                )
              ),
              <.div(^.`class` := "form-group",
                <.label(^.`for` := "inputVersion", "Version"),
                <.input(^.`class` := "form-control", ^.id := "inputVersion", ^.placeholder := "Version",
                  ^.onChange ==> backend.updateModule(moduleIdx, (dep, value) => dep.copy(version = value)),
                  ^.value := version
                )
              ),
              <.div(^.`class` := "modal-footer",
                <.button(^.`type` := "submit", ^.`class` := "btn btn-primary", Attr("data-dismiss") := "modal", "Done")
              )
            )
          )
        ))
      )
    }
    .build

  def dependenciesTable(name: String) = ReactComponentB[(Seq[Dependency], Int, Backend)](name)
    .render{ P =>
      val (deps, editModuleIdx, backend) = P

      def depItem(dep: Dependency, idx: Int) =
        <.tr(
          <.td(dep.module.organization),
          <.td(dep.module.name),
          <.td(dep.version),
          <.td(
            <.a(Attr("data-toggle") := "modal", Attr("data-target") := "#moduleEdit", ^.`class` := "icon-action",
              ^.onClick ==> backend.editModule(idx),
              icon.edit
            )
          ),
          <.td(
            <.a(Attr("data-toggle") := "modal", Attr("data-target") := "#moduleRemove", ^.`class` := "icon-action",
              ^.onClick ==> backend.removeModule(idx),
              icon.remove
            )
          )
        )

      <.div(
        <.p(
          <.button(^.`type` := "button", ^.`class` := "btn btn-default customButton",
            ^.onClick ==> backend.addModule,
            Attr("data-toggle") := "modal", Attr("data-target") := "#moduleEdit",
            "Add"
          )
        ),
        <.table(^.`class` := "table",
          <.thead(
            <.tr(
              <.th("Organization"),
              <.th("Name"),
              <.th("Version"),
              <.th(""),
              <.th("")
            )
          ),
          <.tbody(
            deps.zipWithIndex.map((depItem _).tupled)
          )
        ),
        moduleEditModal((deps.lift(editModuleIdx).fold((Module("", ""), ""))(_.moduleVersion), editModuleIdx, backend))
      )
    }
    .build

  val modules = dependenciesTable("Dependencies")

  val repositories = ReactComponentB[Seq[Remote]]("Repositories")
    .render{ repos =>
      def repoItem(repo: Remote) =
        <.tr(
          <.td(
            <.a(^.href := repo.base,
              repo.base
            )
          )
        )

      val sortedRepos = repos
        .sortBy(repo => repo.base)

      <.table(^.`class` := "table",
        <.thead(
          <.tr(
            <.th("Base URL")
          )
        ),
        <.tbody(
          sortedRepos.map(repoItem)
        )
      )
    }
    .build

  val options = ReactComponentB[(ResolutionOptions, Backend)]("ResolutionOptions")
    .render{ P =>
      val (options, backend) = P

      <.div(
        <.div(^.`class` := "checkbox",
          <.label(
            <.input(^.`type` := "checkbox",
              ^.onChange ==> backend.options.toggleOptional,
              if (options.followOptional) Seq(^.checked := "checked") else Seq(),
              "Follow optional dependencies"
            )
          )
        ),
        <.div(^.`class` := "checkbox",
          <.label(
            <.input(^.`type` := "checkbox",
              ^.onChange ==> backend.options.toggleTest,
              if (options.keepTest) Seq(^.checked := "checked") else Seq(),
              "Keep test dependencies"
            )
          )
        )
      )
    }
    .build

  val resolution = ReactComponentB[(Option[Resolution], Backend)]("Resolution")
    .render{ T =>
      val (resOpt, backend) = T

      resOpt match {
        case Some(res) =>
          <.div(
            <.div(^.`class` := "page-header",
              <.h1("Resolution")
            ),
            resultDependencies((res, backend))
          )

        case None =>
          <.div()
      }
    }
    .build

  val initialState = State(Nil, Seq(coursier.repository.mavenCentral), ResolutionOptions(), None, -1, resolving = false, reverseTree = false, log = Nil)

  val app = ReactComponentB[Unit]("Coursier")
    .initialState(initialState)
    .backend(new Backend(_))
    .render((_,S,B) =>
      <.div(
        <.div(^.role := "tabpanel",
          <.ul(^.`class` := "nav nav-tabs", ^.role := "tablist",
            <.li(^.role := "presentation", ^.`class` := "active",
              <.a(^.href := "#dependencies", ^.aria.controls := "dependencies", ^.role := "tab", Attr("data-toggle") := "tab",
                s"Dependencies (${S.modules.length})"
              )
            ),
            <.li(^.role := "presentation",
              <.a(^.href := "#repositories", ^.aria.controls := "repositories", ^.role := "tab", Attr("data-toggle") := "tab",
                s"Repositories (${S.repositories.length})"
              )
            ),
            <.li(^.role := "presentation",
              <.a(^.href := "#options", ^.aria.controls := "options", ^.role := "tab", Attr("data-toggle") := "tab",
                "Options"
              )
            )
          ),
          <.div(^.`class` := "tab-content",
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane active", ^.id := "dependencies",
              modules((S.modules, S.editModuleIdx, B))
            ),
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane", ^.id := "repositories",
              repositories(S.repositories)
            ),
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane", ^.id := "options",
              options((S.options, B))
            )
          )
        ),

        <.div(<.form(^.onSubmit ==> B.handleResolve,
          <.button(^.`type` := "submit", ^.id := "resolveButton", ^.`class` := "btn btn-lg btn-primary",
            if (S.resolving) ^.disabled := "true" else Attr("active") := "true",
            if (S.resolving) "Resolving..." else "Resolve"
          )
        )),


        <.div(^.role := "tabpanel", ^.id := "results",
          <.ul(^.`class` := "nav nav-tabs", ^.role := "tablist", ^.id := "resTabs",
            <.li(^.role := "presentation", ^.id := "resResTab",
              <.a(^.href := "#resolution", ^.aria.controls := "resolution", ^.role := "tab", Attr("data-toggle") := "tab",
                "Resolution"
              )
            ),
            <.li(^.role := "presentation", ^.id := "resLogTab",
              <.a(^.href := "#log", ^.aria.controls := "log", ^.role := "tab", Attr("data-toggle") := "tab",
                "Log"
              )
            ),
            <.li(^.role := "presentation",
              <.a(^.href := "#depgraph", ^.aria.controls := "depgraph", ^.role := "tab", Attr("data-toggle") := "tab",
                "Graph"
              )
            ),
            <.li(^.role := "presentation",
              <.a(^.href := "#deptreepanel", ^.aria.controls := "deptreepanel", ^.role := "tab", Attr("data-toggle") := "tab",
                "Tree"
              )
            )
          ),
          <.div(^.`class` := "tab-content",
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane", ^.id := "resolution",
              resolution((S.resolutionOpt, B))
            ),
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane", ^.id := "log",
              <.button(^.`type` := "button", ^.`class` := "btn btn-default",
                ^.onClick ==> B.clearLog,
                "Clear"
              ),
              <.div(^.`class` := "well",
                <.ul(^.`class` := "log",
                  S.log.map(e => <.li(e))
                )
              )
            ),
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane", ^.id := "depgraph",
              <.button(^.`type` := "button", ^.`class` := "btn btn-default",
                ^.onClick ==> B.updateDepGraphBtn(S.resolutionOpt.getOrElse(Resolution.empty)),
                "Redraw"
              ),
              <.div(^.id := "depgraphcanvas")
            ),
            <.div(^.role := "tabpanel", ^.`class` := "tab-pane", ^.id := "deptreepanel",
              <.div(^.`class` := "checkbox",
                <.label(
                  <.input(^.`type` := "checkbox",
                    ^.onChange ==> B.toggleReverseTree,
                    if (S.reverseTree) Seq(^.checked := "checked") else Seq(),
                    "Reverse"
                  )
                )
              ),
              <.div(^.id := "deptree")
            )
          )
        )
      )
    )
    .buildU

}
