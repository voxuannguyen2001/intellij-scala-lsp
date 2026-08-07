package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither, Either3 as LspEither3}
import org.eclipse.lsp4j.services.{LanguageClient, TextDocumentService}
import org.jetbrains.scalalsP.intellij.*

import com.intellij.openapi.progress.{EmptyProgressIndicator, ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.Computable

import java.util
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors, LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import scala.jdk.CollectionConverters.*

object ScalaTextDocumentService:

  /** Env var that overrides the request-handler concurrency cap (see resolveMaxConcurrentRequests). */
  private val MaxConcurrentRequestsEnv = "LSP_MAX_CONCURRENT_REQUESTS"

  /** Resolve the size (max concurrency) of the LSP request-handler thread pool.
    *
    * One editor interaction makes the client fire a burst of per-file requests (semanticTokens,
    * inlayHint, codeLens, documentHighlight, foldingRange, documentSymbol, ...), each running a heavy
    * analysis on its own handler thread (semanticTokens parses and builds the stub tree;
    * inlayHint/highlight re-traverse the file). Run too many of these at once and they saturate every
    * core, so each runs many times slower and the daemon appears to hang. The default caps concurrency
    * to a QUARTER of the cores (min 2): it bounds the fan-out (excess requests queue) while leaving the
    * rest of the cores for the analyses themselves. On a high-core machine a half-cores cap still lets
    * enough full-file analyses run concurrently to stall the editor, which is why the default is a
    * quarter rather than a half.
    *
    * Override via LSP_MAX_CONCURRENT_REQUESTS when a different cap suits the machine. A missing,
    * non-numeric, or non-positive value falls back to the default rather than failing startup. */
  def resolveMaxConcurrentRequests(envValue: Option[String], availableProcessors: Int): Int =
    val default = math.max(2, availableProcessors / 4)
    envValue.map(_.trim).filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

// Handles all textDocument LSP requests by delegating to IntelliJ-backed providers.
class ScalaTextDocumentService(projectManager: IntellijProjectManager, val diagnosticsProvider: DiagnosticsProvider) extends TextDocumentService:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized

  // Dedicated, concurrency-capped thread pool for LSP request handlers. Using the common ForkJoinPool
  // causes deadlocks when threads block in smartReadAction (waiting for smart mode) or invokeAndWait
  // (waiting for EDT). The pool size is capped on purpose — see
  // ScalaTextDocumentService.resolveMaxConcurrentRequests for the rationale and the
  // LSP_MAX_CONCURRENT_REQUESTS override.
  // allowCoreThreadTimeOut lets idle handlers die, so the pool doesn't leak across reconnects.
  // CallerRunsPolicy applies back-pressure (runs the task inline) once the queue also fills.
  private val MaxConcurrentRequests: Int =
    ScalaTextDocumentService.resolveMaxConcurrentRequests(
      Option(System.getenv(ScalaTextDocumentService.MaxConcurrentRequestsEnv)),
      Runtime.getRuntime.availableProcessors
    )
  private val lspExecutor: ExecutorService =
    val factory: ThreadFactory = r =>
      val t = Thread(r, "lsp-request-handler")
      t.setDaemon(true)
      t
    val pool = new ThreadPoolExecutor(
      MaxConcurrentRequests, MaxConcurrentRequests, 60L, TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable](256),
      factory,
      new ThreadPoolExecutor.CallerRunsPolicy()
    )
    pool.allowCoreThreadTimeOut(true)
    pool
  /** Run a supplier on the dedicated LSP executor under a cancellable progress indicator.
    *
    * The returned future's cancel() — which lsp4j invokes when the client sends `$/cancelRequest` —
    * cancels the indicator. The IntelliJ read action driving the work (see smartReadAction, which binds
    * to this indicator via wrapProgress) then observes ProgressManager.checkCanceled() and aborts
    * mid-analysis. Without this, a superseded per-file request (e.g. an outdated semanticTokens or
    * inlayHint that the client has already cancelled) keeps burning CPU until it runs to completion. */
  private def supplyAsync[T](f: => T): CompletableFuture[T] =
    val indicator = new EmptyProgressIndicator()
    val future: CompletableFuture[T] = new CompletableFuture[T]:
      override def cancel(mayInterruptIfRunning: Boolean): Boolean =
        indicator.cancel()
        super.cancel(mayInterruptIfRunning)
    val task: Runnable = () =>
      if !indicator.isCanceled then
        try
          val computable: Computable[T] = () => f
          future.complete(ProgressManager.getInstance().runProcess(computable, indicator))
        catch
          case _: ProcessCanceledException => future.cancel(false)
          case e: Throwable                => future.completeExceptionally(e)
    lspExecutor.execute(task)
    future

  /** Release the request-handler thread pool. Called on session end to prevent thread leaks. */
  def dispose(): Unit =
    lspExecutor.shutdownNow()
    ()

  private val requestCounter = new java.util.concurrent.atomic.AtomicLong(0)

  private def logged[T](method: String, params: => String)(f: => CompletableFuture[T]): CompletableFuture[T] =
    val id = requestCounter.incrementAndGet()
    val start = System.currentTimeMillis()
    System.err.println(s"[LSP] --> $method #$id $params")
    val future = f
    future.whenComplete: (_, error) =>
      val elapsed = System.currentTimeMillis() - start
      if error != null then
        val msg = Option(error.getCause).map(_.getMessage).getOrElse(error.getMessage)
        System.err.println(s"[LSP] <-- $method #$id ERROR ${elapsed}ms: $msg")
      else
        System.err.println(s"[LSP] <-- $method #$id ${elapsed}ms")
    future

  private def shortUri(uri: String): String =
    val idx = uri.lastIndexOf('/')
    if idx >= 0 then uri.substring(idx + 1) else uri

  private val documentSync = DocumentSyncManager(projectManager)
  private val definitionProvider = DefinitionProvider(projectManager)
  private val referencesProvider = ReferencesProvider(projectManager)
  private val definitionOrReferencesProvider = DefinitionOrReferencesProvider(projectManager, definitionProvider, referencesProvider)
  private val hoverProvider = HoverProvider(projectManager)
  private val symbolProvider = SymbolProvider(projectManager)
  private val typeDefinitionProvider = TypeDefinitionProvider(projectManager)
  private val implementationProvider = ImplementationProvider(projectManager)
  private val foldingRangeProvider = FoldingRangeProvider(projectManager)
  private val selectionRangeProvider = SelectionRangeProvider(projectManager)
  private val callHierarchyProvider = CallHierarchyProvider(projectManager)
  private val inlayHintProvider = InlayHintProvider(projectManager)
  private val completionProvider = CompletionProvider(projectManager)
  private val codeActionProvider = CodeActionProvider(projectManager)
  private val renameProvider = RenameProvider(projectManager)
  private val typeHierarchyProvider = TypeHierarchyProvider(projectManager)
  private val signatureHelpProvider = SignatureHelpProvider(projectManager)
  private val formattingProvider = FormattingProvider(projectManager)
  private val onTypeFormattingProvider = OnTypeFormattingProvider(projectManager)
  private val documentLinkProvider = DocumentLinkProvider(projectManager)
  private val semanticTokensProvider = SemanticTokensProvider(projectManager)
  private val documentHighlightProvider = DocumentHighlightProvider(projectManager)
  private val codeLensProvider = CodeLensProvider(projectManager, List(SuperMethodCodeLens()))

  def connect(client: LanguageClient): Unit =
    this.client = client
    diagnosticsProvider.connect(client)

  /** Get the last references result with usage types (for executeCommand access). */
  def getLastReferencesWithTypes: Seq[ReferenceResult] =
    referencesProvider.getLastResultsWithTypes

  def registerDaemonListener(): Unit =
    diagnosticsProvider.registerDaemonListener()

  // --- Document Synchronization ---

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    System.err.println(s"[LSP] notify textDocument/didOpen ${shortUri(uri)}")
    documentSync.didOpen(uri, params.getTextDocument.getText)
    diagnosticsProvider.trackOpen(uri)

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    System.err.println(s"[LSP] notify textDocument/didChange ${shortUri(uri)}")
    val changes = params.getContentChanges.asScala.toSeq
    changes.headOption.foreach: change =>
      documentSync.didChange(uri, change.getText)
    diagnosticsProvider.scheduleAnalysis(uri) // debounced, 1s delay

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    System.err.println(s"[LSP] notify textDocument/didClose ${shortUri(uri)}")
    documentSync.didClose(uri)
    diagnosticsProvider.trackClose(uri)

  override def didSave(params: DidSaveTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    System.err.println(s"[LSP] notify textDocument/didSave ${shortUri(uri)}")
    documentSync.didSave(uri)
    diagnosticsProvider.scheduleAnalysis(uri, delayMs = 100) // analyze promptly on save

  // --- Navigation ---

  override def definition(params: DefinitionParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    logged("textDocument/definition", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        val locations = definitionOrReferencesProvider.getDefinitionOrReferences(
          params.getTextDocument.getUri,
          params.getPosition
        )
        LspEither.forLeft(locations.asJava)

  override def references(params: ReferenceParams): CompletableFuture[util.List[? <: Location]] =
    logged("textDocument/references", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        referencesProvider.findReferences(
          params.getTextDocument.getUri,
          params.getPosition,
          params.getContext.isIncludeDeclaration
        ).asJava

  override def hover(params: HoverParams): CompletableFuture[Hover] =
    logged("textDocument/hover", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        hoverProvider.getHover(
          params.getTextDocument.getUri,
          params.getPosition
        ).orNull

  override def typeDefinition(params: TypeDefinitionParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    logged("textDocument/typeDefinition", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        val locations = typeDefinitionProvider.getTypeDefinition(
          params.getTextDocument.getUri,
          params.getPosition
        )
        LspEither.forLeft(locations.asJava)

  override def implementation(params: ImplementationParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    logged("textDocument/implementation", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        val locations = implementationProvider.getImplementations(
          params.getTextDocument.getUri,
          params.getPosition
        )
        LspEither.forLeft(locations.asJava)

  override def documentSymbol(params: DocumentSymbolParams): CompletableFuture[util.List[LspEither[SymbolInformation, DocumentSymbol]]] =
    logged("textDocument/documentSymbol", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        symbolProvider.documentSymbols(params.getTextDocument.getUri)
          .map(ds => LspEither.forRight[SymbolInformation, DocumentSymbol](ds))
          .asJava

  override def foldingRange(params: FoldingRangeRequestParams): CompletableFuture[util.List[FoldingRange]] =
    logged("textDocument/foldingRange", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        foldingRangeProvider.getFoldingRanges(params.getTextDocument.getUri).asJava

  override def selectionRange(params: SelectionRangeParams): CompletableFuture[util.List[SelectionRange]] =
    logged("textDocument/selectionRange", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        val positions = params.getPositions.asScala.toSeq
        selectionRangeProvider.getSelectionRanges(params.getTextDocument.getUri, positions).asJava

  // --- Call Hierarchy ---

  override def prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture[util.List[CallHierarchyItem]] =
    logged("textDocument/prepareCallHierarchy", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        callHierarchyProvider.prepare(
          params.getTextDocument.getUri,
          params.getPosition
        ).asJava

  override def callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    logged("callHierarchy/incomingCalls", params.getItem.getName):
      supplyAsync:
        callHierarchyProvider.incomingCalls(params.getItem).asJava

  override def callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    logged("callHierarchy/outgoingCalls", params.getItem.getName):
      supplyAsync:
        callHierarchyProvider.outgoingCalls(params.getItem).asJava

  // --- Inlay Hints ---

  override def inlayHint(params: InlayHintParams): CompletableFuture[util.List[InlayHint]] =
    logged("textDocument/inlayHint", s"${shortUri(params.getTextDocument.getUri)}:${params.getRange.getStart.getLine}-${params.getRange.getEnd.getLine}"):
      supplyAsync:
        inlayHintProvider.getInlayHints(
          params.getTextDocument.getUri,
          params.getRange
        ).asJava

  override def resolveInlayHint(hint: InlayHint): CompletableFuture[InlayHint] =
    logged("inlayHint/resolve", ""):
      supplyAsync:
        inlayHintProvider.resolveInlayHint(hint)

  // --- Completion ---

  override def completion(params: CompletionParams): CompletableFuture[LspEither[util.List[CompletionItem], CompletionList]] =
    logged("textDocument/completion", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        val items = completionProvider.getCompletions(
          params.getTextDocument.getUri,
          params.getPosition
        )
        LspEither.forLeft(items.asJava)

  override def resolveCompletionItem(unresolved: CompletionItem): CompletableFuture[CompletionItem] =
    logged("completionItem/resolve", unresolved.getLabel):
      supplyAsync:
        completionProvider.resolveCompletion(unresolved)

  // --- Signature Help ---

  override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
    logged("textDocument/signatureHelp", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        signatureHelpProvider.getSignatureHelp(
          params.getTextDocument.getUri,
          params.getPosition
        ).orNull

  // --- Code Actions ---

  override def codeAction(params: CodeActionParams): CompletableFuture[util.List[LspEither[Command, CodeAction]]] =
    logged("textDocument/codeAction", s"${shortUri(params.getTextDocument.getUri)}:${params.getRange.getStart.getLine}-${params.getRange.getEnd.getLine}"):
      supplyAsync:
        codeActionProvider.getCodeActions(
          params.getTextDocument.getUri,
          params.getRange,
          params.getContext
        ).map(ca => LspEither.forRight[Command, CodeAction](ca)).asJava

  override def resolveCodeAction(unresolved: CodeAction): CompletableFuture[CodeAction] =
    logged("codeAction/resolve", unresolved.getTitle):
      supplyAsync:
        codeActionProvider.resolveCodeAction(unresolved)

  // --- Rename ---

  override def prepareRename(params: PrepareRenameParams): CompletableFuture[LspEither3[Range, PrepareRenameResult, PrepareRenameDefaultBehavior]] =
    logged("textDocument/prepareRename", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        val result = renameProvider.prepareRename(
          params.getTextDocument.getUri,
          params.getPosition
        )
        if result != null then LspEither3.forSecond(result)
        else null

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    logged("textDocument/rename", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter} -> ${params.getNewName}"):
      supplyAsync:
        renameProvider.rename(
          params.getTextDocument.getUri,
          params.getPosition,
          params.getNewName
        )

  // --- Type Hierarchy ---

  override def prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    logged("textDocument/prepareTypeHierarchy", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        typeHierarchyProvider.prepare(
          params.getTextDocument.getUri,
          params.getPosition
        ).asJava

  override def typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    logged("typeHierarchy/supertypes", params.getItem.getName):
      supplyAsync:
        typeHierarchyProvider.supertypes(params.getItem).asJava

  override def typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    logged("typeHierarchy/subtypes", params.getItem.getName):
      supplyAsync:
        typeHierarchyProvider.subtypes(params.getItem).asJava

  // --- Formatting ---

  override def formatting(params: DocumentFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    logged("textDocument/formatting", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        formattingProvider.getFormatting(params.getTextDocument.getUri).asJava

  override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    logged("textDocument/rangeFormatting", s"${shortUri(params.getTextDocument.getUri)}:${params.getRange.getStart.getLine}-${params.getRange.getEnd.getLine}"):
      supplyAsync:
        formattingProvider.getRangeFormatting(
          params.getTextDocument.getUri,
          params.getRange
        ).asJava

  override def onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    logged("textDocument/onTypeFormatting", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        onTypeFormattingProvider.onTypeFormatting(
          params.getTextDocument.getUri,
          params.getPosition,
          params.getCh
        ).asJava

  // --- Document Links ---

  override def documentLink(params: DocumentLinkParams): CompletableFuture[util.List[DocumentLink]] =
    logged("textDocument/documentLink", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        documentLinkProvider.getDocumentLinks(params.getTextDocument.getUri).asJava

  // --- Semantic Tokens ---

  override def semanticTokensFull(params: SemanticTokensParams): CompletableFuture[SemanticTokens] =
    logged("textDocument/semanticTokens/full", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        semanticTokensProvider.getSemanticTokensFull(params.getTextDocument.getUri)

  override def semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture[SemanticTokens] =
    logged("textDocument/semanticTokens/range", s"${shortUri(params.getTextDocument.getUri)}:${params.getRange.getStart.getLine}-${params.getRange.getEnd.getLine}"):
      supplyAsync:
        semanticTokensProvider.getSemanticTokensRange(
          params.getTextDocument.getUri,
          params.getRange
        )

  // --- Document Highlights ---

  override def documentHighlight(params: DocumentHighlightParams): CompletableFuture[util.List[? <: DocumentHighlight]] =
    logged("textDocument/documentHighlight", s"${shortUri(params.getTextDocument.getUri)}:${params.getPosition.getLine}:${params.getPosition.getCharacter}"):
      supplyAsync:
        documentHighlightProvider.getDocumentHighlights(
          params.getTextDocument.getUri,
          params.getPosition
        ).asJava

  // --- Code Lens ---

  // --- Code Lens ---

  override def codeLens(params: CodeLensParams): CompletableFuture[util.List[? <: CodeLens]] =
    logged("textDocument/codeLens", shortUri(params.getTextDocument.getUri)):
      supplyAsync:
        codeLensProvider.getCodeLenses(params.getTextDocument.getUri).asJava

  override def resolveCodeLens(codeLens: CodeLens): CompletableFuture[CodeLens] =
    logged("codeLens/resolve", ""):
      supplyAsync:
        codeLensProvider.resolveCodeLens(codeLens)
