package com.oxidecode.autocomplete.edit

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.project.Project
import com.oxidecode.settings.OxideCodeSettings
import com.oxidecode.utils.OxideCodeConstants
import com.oxidecode.utils.getKeyStrokesForAction
import com.oxidecode.utils.parseKeyStrokesToPrint
import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.KeyEvent

/**
 * Application-level router that installs EditorActionManager handlers once
 * and delegates behavior to the appropriate project's RecentEditsTracker.
 *
 * This service dynamically intercepts EditorActions based on the user's keymap configuration
 * for AcceptEditCompletionAction and RejectEditCompletionAction, allowing users to customize
 * the keystrokes used for accepting/rejecting autocomplete suggestions.
 */
@Service(Service.Level.APP)
class EditorActionsRouterService : Disposable {
    private val originals: MutableMap<String, EditorActionHandler> = mutableMapOf()

    // When TAB acceptance happens, Swing can still deliver a subsequent KEY_TYPED '\t' event.
    // In Gateway split mode this is the main cause of the "accepted suggestion + extra tab inserted" bug.
    // We suppress that follow-up typed TAB for a very short window.
    @Volatile
    private var gatewayClientSuppressTabTypedUntilMs: Long = 0

    // Gateway split mode note:
    // Even if we intercept the editor action handler, TAB can still be processed as a regular key event
    // and forwarded/applied, causing an extra tab after acceptance.
    //
    // On the Gateway CLIENT we add an IdeEventQueue dispatcher that consumes the raw TAB key event
    // when a Sweep suggestion is visible.
    private val gatewayClientTabDispatcher: IdeEventQueue.EventDispatcher =
        IdeEventQueue.EventDispatcher dispatcher@{ e: AWTEvent ->
            if (OxideCodeConstants.GATEWAY_MODE != OxideCodeConstants.GatewayMode.CLIENT) return@dispatcher false

            val ke = e as? KeyEvent ?: return@dispatcher false
            if (ke.isConsumed) return@dispatcher true

            // Suppress the follow-up KEY_TYPED tab after we accepted via TAB.
            if (ke.id == KeyEvent.KEY_TYPED) {
                val now = System.currentTimeMillis()
                val isTypedTab = ke.keyChar == '\t' && !ke.isAltDown && !ke.isControlDown && !ke.isMetaDown && !ke.isShiftDown
                if (isTypedTab && now < gatewayClientSuppressTabTypedUntilMs) {
                    ke.consume()
                    return@dispatcher true
                }
                return@dispatcher false
            }

            if (ke.id != KeyEvent.KEY_PRESSED) return@dispatcher false

            val isPlainTab =
                ke.keyCode == KeyEvent.VK_TAB &&
                    !ke.isAltDown &&
                    !ke.isControlDown &&
                    !ke.isMetaDown &&
                    !ke.isShiftDown

            if (!isPlainTab) return@dispatcher false

            // Only if TAB is the configured accept editor action
            if (IdeActions.ACTION_EDITOR_TAB !in activeAcceptActions) return@dispatcher false

            fun findEditorComponent(component: Component?): EditorComponentImpl? {
                var c = component
                while (c != null) {
                    if (c is EditorComponentImpl) return c
                    c = c.parent
                }
                return null
            }

            val editorComponent = findEditorComponent(ke.component) ?: return@dispatcher false
            val editor = editorComponent.editor
            val tracker = trackerFor(editor) ?: return@dispatcher false
            if (!tracker.isCompletionShown) return@dispatcher false

            tracker.acceptSuggestion()
            gatewayClientSuppressTabTypedUntilMs = System.currentTimeMillis() + 250
            ke.consume()
            true
        }

    // Cache of currently active accept/reject action IDs (updated when keymap changes)
    @Volatile
    private var activeAcceptActions: Set<String> = emptySet()

    @Volatile
    private var activeRejectActions: Set<String> = emptySet()

    private var lastAcceptKeystrokes: String = ""
    private var lastRejectKeystrokes: String = ""

    companion object {
        fun getInstance(): EditorActionsRouterService =
            ApplicationManager
                .getApplication()
                .getService(EditorActionsRouterService::class.java)

        private const val ACCEPT_ACTION_ID = AcceptEditCompletionAction.ACTION_ID
        private const val REJECT_ACTION_ID = "com.oxidecode.autocomplete.edit.RejectEditCompletionAction"
    }

    init {
        // Initialize baseline for telemetry
        lastAcceptKeystrokes = getKeystrokesString(ACCEPT_ACTION_ID)
        lastRejectKeystrokes = getKeystrokesString(REJECT_ACTION_ID)

        // Update active actions cache
        updateActiveActions()

        // Install all possible handlers once
        installHandlers()

        // In Gateway split mode, also consume the raw TAB event on the CLIENT so it doesn't
        // fall through to the normal indent/tab handler.
        if (OxideCodeConstants.GATEWAY_MODE == OxideCodeConstants.GatewayMode.CLIENT) {
            IdeEventQueue.getInstance().addDispatcher(gatewayClientTabDispatcher, this)
        }

        // Listen for keymap changes and update the cache (no reinstall needed)
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                KeymapManagerListener.TOPIC,
                object : KeymapManagerListener {
                    override fun activeKeymapChanged(keymap: com.intellij.openapi.keymap.Keymap?) {
                        checkAndTrackKeystrokeChanges()
                        updateActiveActions()
                    }
                },
            )
    }

    /**
     * Updates the cached set of active accept/reject action IDs based on current keymap.
     * Called on init and whenever keymap changes. No handler reinstallation needed.
     */
    private fun updateActiveActions() {
        activeAcceptActions =
            getKeyStrokesForAction(ACCEPT_ACTION_ID)
                .let { KeystrokeToEditorActionMapper.mapToEditorActions(it) }
                .toSet()

        activeRejectActions =
            getKeyStrokesForAction(REJECT_ACTION_ID)
                .let { KeystrokeToEditorActionMapper.mapToEditorActions(it) }
                .toSet()
    }

    /**
     * Installs all possible action handlers once.
     * Handlers check activeAcceptActions/activeRejectActions at runtime to determine behavior.
     */
    private fun installHandlers() {
        val eam = EditorActionManager.getInstance()

        fun wrap(
            actionId: String,
            wrapper: (EditorActionHandler) -> EditorActionHandler,
        ) {
            if (originals.containsKey(actionId)) return
            val original = eam.getActionHandler(actionId)
            originals[actionId] = original
            eam.setActionHandler(actionId, wrapper(original))
        }

        // Install handlers for ALL possible EditorActions that could be bound to accept/reject.
        // At runtime, we check activeAcceptActions/activeRejectActions to determine behavior.
        // Note: Only include EditorActions here, not regular Actions (like CODE_COMPLETION)
        val allPossibleActions =
            listOf(
                IdeActions.ACTION_EDITOR_TAB,
                IdeActions.ACTION_EDITOR_UNINDENT_SELECTION,
                IdeActions.ACTION_EDITOR_ENTER,
                IdeActions.ACTION_EDITOR_ESCAPE,
                IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT,
                IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT,
                IdeActions.ACTION_EDITOR_MOVE_CARET_UP,
                IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN,
                IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT_WITH_SELECTION,
                IdeActions.ACTION_EDITOR_DELETE,
                IdeActions.ACTION_EDITOR_BACKSPACE,
                IdeActions.ACTION_EDITOR_NEXT_WORD,
                IdeActions.ACTION_EDITOR_PREVIOUS_WORD,
                IdeActions.ACTION_EDITOR_MOVE_LINE_END,
                IdeActions.ACTION_EDITOR_MOVE_LINE_START,
            )

        // Wrap all possible actions with runtime checks
        allPossibleActions.forEach { actionId ->
            wrap(actionId) { original ->
                object : EditorActionHandler() {
                    override fun doExecute(
                        editor: Editor,
                        caret: Caret?,
                        dataContext: DataContext,
                    ) {
                        // Guard: don't process actions for disposed editors/projects
                        if (editor.isDisposed || editor.project?.isDisposed == true) {
                            return
                        }

                        // In Gateway split mode, editor keystrokes are applied on both frontend and backend.
                        // If we swallow the action on the HOST (backend), the backend state won't change and
                        // the editor will quickly revert due to state synchronization.
                        //
                        // Therefore:
                        // - On HOST: never intercept accept/reject; always execute original behavior.
                        // - On CLIENT/NA: intercept based on user's keymap.
                        if (OxideCodeConstants.GATEWAY_MODE == OxideCodeConstants.GatewayMode.HOST) {
                            original.execute(editor, caret, dataContext)
                            return
                        }

                        if (true) {
                            val tracker = trackerFor(editor)

                            // Special case: Alt-Right (Next word) with acceptWordOnRightArrow setting
                            if (actionId == IdeActions.ACTION_EDITOR_NEXT_WORD &&
                                OxideCodeSettings.getInstance().acceptWordOnRightArrow &&
                                tracker?.acceptNextWord() == true
                            ) {
                                return
                            }

                            if (tracker == null) {
                                original.execute(editor, caret, dataContext)
                                return
                            }

                            // Runtime check: Is this action bound to accept?
                            if (actionId in activeAcceptActions && tracker.isCompletionShown) {
                                tracker.acceptSuggestion()
                                return
                            }

                            // Runtime check: Is this action bound to reject?
                            if (actionId in activeRejectActions && tracker.isCompletionShown) {
                                tracker.rejectSuggestion()
                                return
                            }

                            // Not bound to accept/reject, or no completion shown - execute original
                            original.execute(editor, caret, dataContext)
                        } else {
                            return
                        }
                    }

                    override fun isEnabledForCaret(
                        editor: Editor,
                        caret: Caret,
                        dataContext: DataContext,
                    ): Boolean = originals[actionId]?.isEnabled(editor, caret, dataContext) ?: true
                }
            }
        }

        wrap(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE) { original ->
            object : EditorActionHandler() {
                override fun doExecute(
                    editor: Editor,
                    caret: Caret?,
                    dataContext: DataContext,
                ) {
                    // Guard: don't process actions for disposed editors/projects
                    if (editor.isDisposed || editor.project?.isDisposed == true) {
                        return
                    }

                    // See comment above: on HOST we must execute original to avoid frontend state reverting.
                    if (OxideCodeConstants.GATEWAY_MODE == OxideCodeConstants.GatewayMode.HOST) {
                        original.execute(editor, caret, dataContext)
                        return
                    }

                    if (true) {
                        val tracker = trackerFor(editor) ?: return original.execute(editor, caret, dataContext)

                        // Only intercept if TAB is configured as the accept key
                        if (tracker.isCompletionShown && IdeActions.ACTION_EDITOR_TAB in activeAcceptActions) {
                            tracker.acceptSuggestion()
                        } else {
                            original.execute(editor, caret, dataContext)
                        }
                    } else {
                        return
                    }
                }

                override fun isEnabledForCaret(
                    editor: Editor,
                    caret: Caret,
                    dataContext: DataContext,
                ): Boolean = originals[IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE]?.isEnabled(editor, caret, dataContext) ?: true
            }
        }

        // We only mark metadata for first-time lookup usage; we do not change behavior
        wrap(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM) { original ->
            object : EditorActionHandler() {
                override fun doExecute(
                    editor: Editor,
                    caret: Caret?,
                    dataContext: DataContext,
                ) {
                    // Guard: don't process actions for disposed editors/projects
                    if (editor.isDisposed || editor.project?.isDisposed == true) {
                        return
                    }

                    original.execute(editor, caret, dataContext)
                }

                override fun isEnabledForCaret(
                    editor: Editor,
                    caret: Caret,
                    dataContext: DataContext,
                ): Boolean = originals[IdeActions.ACTION_CHOOSE_LOOKUP_ITEM]?.isEnabled(editor, caret, dataContext) ?: true
            }
        }

        // Note: All accept/reject keybindings are now handled dynamically via the allPossibleActions loop above.
        // Only the keybindings explicitly configured by the user will trigger accept/reject behavior.
        // Special cases (caret movement rejection, Alt-Right accept word) are also handled in that loop.
    }

    private fun checkAndTrackKeystrokeChanges() {
        val currentAccept = getKeystrokesString(ACCEPT_ACTION_ID)
        if (currentAccept != lastAcceptKeystrokes) {
            lastAcceptKeystrokes = currentAccept
        }

        val currentReject = getKeystrokesString(REJECT_ACTION_ID)
        if (currentReject != lastRejectKeystrokes) {
            lastRejectKeystrokes = currentReject
        }
    }

    private fun getKeystrokesString(actionId: String): String =
        getKeyStrokesForAction(actionId)
            .mapNotNull { parseKeyStrokesToPrint(it) }
            .sorted()
            .joinToString(", ")

    private fun trackerFor(editor: Editor): RecentEditsTracker? {
        val project: Project = editor.project ?: return null
        // Only delegate if the feature is enabled to avoid instantiating trackers unnecessarily
        return if (OxideCodeSettings.getInstance().nextEditPredictionFlagOn) {
            RecentEditsTracker.getInstance(project)
        } else {
            null
        }
    }

    override fun dispose() {
        // Restore original handlers on the EDT to leave IDE state clean on plugin unload
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            val eam = EditorActionManager.getInstance()
            originals.forEach { (id, original) ->
                eam.setActionHandler(id, original)
            }
            originals.clear()
        }
    }
}
