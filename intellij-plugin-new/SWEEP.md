# Sweep AI JetBrains Plugin

We're building a JetBrains plugin and a copy of IntelliJ Community is in `vendor/intellij-community` for reference.

### Build Output
The built plugin artifacts are located in `build/distributions/`

## Code Style Guidelines

### Debug Logging
- When the user asks you to add logs, **use `println` instead of `LOG.debug`** for debug logging
- This ensures that debug logs are always printed, even if the log level is set to `INFO` or higher
- Example:
  ```kotlin
  // ✅ Correct - Always prints debug logs
  println("Debug message")
  
  // ❌ Avoid - Logs may not be printed if log level is set to INFO or higher
  LOG.debug("Debug message")
  ```

### Threading and EDT Operations
- **Always use `ApplicationManager.getApplication().invokeLater`** instead of `SwingUtilities.invokeLater` for executing code on the Event Dispatch Thread (EDT)
- This is the IntelliJ Platform best practice and provides better integration with IntelliJ's application lifecycle and threading model
- Example:
  ```kotlin
  // ✅ Correct - IntelliJ Platform way
  ApplicationManager.getApplication().invokeLater {
      // UI updates here
  }
  
  // ❌ Avoid - Standard Swing way
  SwingUtilities.invokeLater {
      // UI updates here
  }
  ```

### Disposable Parent Guidelines
- **Never set project as the parent disposable** - Instead, always use `SweepProjectService.getInstance(project)` as the parent disposable
- This ensures proper lifecycle management and prevents memory leaks
- Example:
  ```kotlin
  // ✅ Correct - Using SweepProjectService as parent disposable
  project.messageBus.connect(SweepProjectService.getInstance(project)).subscribe(
      ToolWindowManagerListener.TOPIC,
      object : ToolWindowManagerListener {
          // ...
      }
  )
  
  // ❌ Avoid - Using project directly as parent disposable
  project.messageBus.connect(project).subscribe(
      ToolWindowManagerListener.TOPIC,
      object : ToolWindowManagerListener {
          // ...
      }
  )
  ```

### Theme-Aware Colors
- **Use `JBColor(lightModeColor, darkModeColor)`** for colors that need to adapt to light/dark themes
- This automatically switches between colors based on the current theme
- Example:
  ```kotlin
  // ✅ Correct - Theme-aware colors
  private val BACKGROUND_COLOR = JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 20)) // black/8 for light, white/8 for dark
  
  // ❌ Avoid - Fixed colors that don't adapt to themes
  private val BACKGROUND_COLOR = Color(0, 0, 0, 20) // Only works well in one theme
  ```
  
### MouseListener Usage
- **Prefer to use `mouseReleased` event whenever possible for listening for mouse clicks on UI elements because it is less buggy when user drags**
- Example:
```kotlin
    private val mouseListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let { event -> // handle mouse click
                 }
            }
        }
```

### Icon Colorization
- **Use `colorizeIcon(icon, color)`** from `dev.sweep.assistant.utils` instead of the deprecated `IconUtil.colorize`
- The `IconUtil.colorize` method was removed in IntelliJ IDEA 2025.2, so we provide our own implementation
- Example:
  ```kotlin
  // ✅ Correct - Using our custom colorizeIcon utility
  import dev.sweep.assistant.utils.colorizeIcon
  
  headerLabel.updateRightIcon(colorizeIcon(AllIcons.Actions.Checked, JBColor.green))
  
  // ❌ Avoid - Deprecated method that no longer exists
  headerLabel.updateRightIcon(IconUtil.colorize(AllIcons.Actions.Checked, JBColor.green))
  ```

### Safe Null Handling
- **Use safe call operator `?.let` instead of `!!`** for nullable values to avoid potential crashes
- Example:
  ```kotlin
  // ✅ Correct - Safe null handling
  val currentEditor = project?.let { FileEditorManager.getInstance(it).selectedTextEditor }
  
  // ❌ Avoid - Force unwrapping that can crash
  val currentEditor = FileEditorManager.getInstance(project!!).selectedTextEditor
  ```

### Import Style
- **Import classes directly** instead of using full package paths in code to keep lines readable
- Add the import at the top of the file rather than using the full qualified name
- Example:
  ```kotlin
  // ✅ Correct - Clean import and usage
  import com.intellij.openapi.fileEditor.FileEditorManager
  
  val currentEditor = project?.let { FileEditorManager.getInstance(it).selectedTextEditor }
  
  // ❌ Avoid - Full path makes lines too long
  val currentEditor = project?.let { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(it).selectedTextEditor }
  ```

### Dark Mode Icons
- **IntelliJ automatically handles dark mode icon variants** - No manual theme detection needed
- Create a `_dark` variant of your icon file and IntelliJ will automatically use it in dark mode
- Example file structure:
  ```
  src/main/resources/icons/
  ├── myIcon.svg        # Light mode version
  └── myIcon_dark.svg   # Dark mode version (automatically used in dark mode)
  ```
- In SweepIcons.kt, simply load the base icon:
  ```kotlin
  // ✅ Correct - IntelliJ handles _dark variant automatically
  val MyIcon = loadIcon("/icons/myIcon.svg")
  
  // ❌ Avoid - Manual theme detection (unnecessary)
  val MyIcon get() = if (isIDEDarkMode()) loadIcon("/icons/myIcon_dark.svg") else loadIcon("/icons/myIcon.svg")
  ```
- Dark mode icons should typically use white (`#FFFFFF`) or light colors for visibility
- Light mode icons should use dark colors or `currentColor` for theme adaptation

### Listener Disposal
- **Always store listeners as properties and remove them in dispose()** to prevent memory leaks
- This applies to all types of listeners: MouseListener, ActionListener, DocumentListener, etc.
- Make listeners properties so they can be properly removed when the component is disposed
- Example with MouseAdapter:
  ```kotlin
  // ✅ Correct - Listener stored as property and properly disposed
  class MyComponent : Disposable {
      val component = JBLabel()
      
      private val mouseAdapter = object : MouseAdapter() {
          override fun mouseClicked(e: MouseEvent?) {
              handleClick()
          }
      }
      
      init {
          component.addMouseListener(mouseAdapter)
      }
      
      override fun dispose() {
          component.removeMouseListener(mouseAdapter)
      }
  }
  
  // ❌ Avoid - Anonymous listener that can't be removed
  class MyComponent : Disposable {
      val component = JBLabel().apply {
          addMouseListener(object : MouseAdapter() {
              override fun mouseClicked(e: MouseEvent?) {
                  handleClick()
              }
          })
      }
      
      override fun dispose() {
          // No way to remove the listener!
      }
  }
  ```
- Other common listeners that need disposal:
  ```kotlin
  // Document listeners
  private val documentListener = object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) { /* ... */ }
  }
  
  // Action listeners
  private val actionListener = ActionListener { /* ... */ }
  
  // Tree selection listeners
  private val treeSelectionListener = TreeSelectionListener { /* ... */ }
  
  // Always remove in dispose():
  override fun dispose() {
      document.removeDocumentListener(documentListener)
      button.removeActionListener(actionListener)
      tree.removeTreeSelectionListener(treeSelectionListener)
  }
  ```

### Service Registration
- **Don't manually register project services in plugin.xml** - The `@Service(Service.Level.PROJECT)` annotation handles registration automatically
- Only register services manually if they need special configuration or dependencies
- Example:
  ```kotlin
  // ✅ Correct - Annotation-based registration (no plugin.xml entry needed)
  @Service(Service.Level.PROJECT)
  class MyProjectService(private val project: Project) : Disposable {
      companion object {
          fun getInstance(project: Project): MyProjectService =
              project.getService(MyProjectService::class.java)
      }
  }
  
  // ❌ Avoid - Manual plugin.xml registration for simple services
  // <projectService serviceImplementation="com.example.MyProjectService"/>
  ```

### API Design - Avoid Unnecessary Helper Methods
- **Don't create pointless wrapper methods** - If callers can access the implementation directly, don't add a helper
- **Make fields/functions public instead of creating wrappers** when appropriate
- Example:
  ```kotlin
  // ❌ Avoid - Unnecessary helper method
  class MyClass(private val fileEditor: TextEditor) {
      fun getFilePath(): String = fileEditor.file?.path ?: ""
  }
  // Usage: myClass.getFilePath()
  
  // ✅ Correct - Direct access via property
  class MyClass(private val fileEditor: TextEditor) {
      val filePath: String
          get() = fileEditor.file?.path ?: ""
  }
  // Usage: myClass.filePath
  
  // ❌ Avoid - Unnecessary wrapper method
  class MyClass {
      private var updateFunction: (() -> Unit)? = null
      fun update() { updateFunction?.invoke() }
  }
  
  // ✅ Correct - Public function for direct access
  class MyClass {
      var updateFunction: (() -> Unit)? = null
      // Usage: myClass.updateFunction?.invoke()
  }
  ```

### Document Text Access
- **Use `document.text`** when you need the full document content as a String
- **Use `document.charsSequence.subSequence(start, end)`** when you only need a portion of the document text
- This avoids loading the entire document text onto the heap, which is more memory-efficient for large files
- Note: `document.text` and `document.charsSequence.toString()` are equivalent - both call `getImmutableCharSequence().toString()` internally, so prefer the cleaner `document.text`
- Example:
  ```kotlin
  // ✅ Correct - When you need the full document text
  val fullText = document.text

  // ✅ Correct - Only loads the needed portion, returns a CharSequence view
  val rangeText = document.charsSequence.subSequence(startOffset, endOffset)

  // ❌ Avoid - Verbose and does the same as document.text
  val fullText = document.charsSequence.toString()

  // ❌ Avoid - Loads entire document text onto the heap first
  val rangeText = document.text.substring(startOffset, endOffset)

  // ❌ Avoid - Also loads entire document and creates unnecessary TextRange object
  val rangeText = document.getText(TextRange(startOffset, endOffset))
  ```
- `CharSequence` supports common operations like `isNotEmpty()`, `endsWith()`, `startsWith()`, etc.
- Only convert to `String` if you specifically need a `String` type

## MessageList Thread-Safety and Usage

MessageList is now a thread-safe store guarded by a ReentrantReadWriteLock. It no longer implements MutableList. Follow these rules to avoid races, UI-thread violations, and ConcurrentModificationException.

- Do
  - Read using snapshot() to get an immutable copy: `val msgs = MessageList.getInstance(project).snapshot()`
  - Use helpers for reads: `size()`, `getOrNull(i)`, `lastOrNull { ... }`, `indexOfLast { ... }`
  - Write with short critical sections: `addMessage(m)`, `setAt(i, m)`, `clearAndAddAll(list, resetConversationId)`
  - Treat Message as immutable: always use `copy(...)` and replace the element; never mutate fields in place
  - Pass messages to APIs as a List by using `snapshot()` (do not pass MessageList itself)
  - Keep IO and UI off-lock. MessageList never does IO/UI while holding locks; keep that pattern at call sites too

- Don't
  - Don't index with brackets for reads: use `get(index)`/`getOrNull(index)` instead of `messageList[i]`
  - Don't use the `.size` property: use `size()`
  - Don't mutate nested fields in place (e.g., `message.annotations?.completedToolCalls?.add(...)`); create a new Message via `copy()` and call `setAt`
  - Don't write UI code inside MessageList critical sections; UI updates must be posted to the EDT
  - Don't pass MessageList to APIs expecting `List<Message>`; pass `snapshot()` instead

- Conversation ID and model selection
  - `conversationId` and `selectedModel` are backed by Atomics and are thread-safe to get/set
  - Setting `conversationId` will update TabManager on the EDT automatically; you don't need to call TabManager yourself

- Common migrations (before → after)
  - Read all messages for network call
    ```kotlin
    // Before
    val messages = MessageList.getInstance(project)
    
    // After
    val messages = MessageList.getInstance(project).snapshot()
    ```
  - Update last user message annotations
    ```kotlin
    val ml = MessageList.getInstance(project)
    val idx = ml.indexOfLast { it.role == MessageRole.USER }
    if (idx >= 0) {
        val old = ml.getOrNull(idx) ?: return
        val new = old.copy(
            annotations = (old.annotations ?: Annotations()).copy(currentFilePath = path)
        )
        ml.setAt(idx, new)
    }
    ```
  - Replace slice/size usage
    ```kotlin
    val ml = MessageList.getInstance(project)
    val safeEnd = end.coerceAtMost(ml.size())
    val leading = ml.snapshot().slice(0 until safeEnd)
    ```
  - Don't mutate in place
    ```kotlin
    // Before (avoid)
    message.annotations?.completedToolCalls?.addAll(calls)
    
    // After
    val updated = message.copy(
        annotations = (message.annotations ?: Annotations()).copy(
            completedToolCalls = ((message.annotations?.completedToolCalls ?: emptyList()) + calls).toMutableList()
        )
    )
    MessageList.getInstance(project).setAt(index, updated)
    ```

Notes
- Writes are safe from any thread; only UI updates must be scheduled on the EDT via `ApplicationManager.getApplication().invokeLater { ... }`
- If you need to iterate multiple times, store one `snapshot()` and reuse it instead of calling `snapshot()` repeatedly

### Deadlock prevention (critical)

- Never call `invokeAndWait` while holding any lock (including MessageList's read/write lock) or from code paths that might be under MessageList operations; prefer `invokeLater`.
- No UI or cross-service calls while holding locks. Pattern: `snapshot()` -> compute off-lock -> `invokeLater { UI }` / `executeOnPooledThread { IO }` -> minimal `setAt`/`addMessage` write.
- No IO/Network under lock. Collect inputs from `snapshot()` first; perform IO on pooled threads; then do tiny writes (`setAt`, `clearAndAddAll`) only.
- Avoid nested locks and lock-order inversions. Do not `synchronized(messageList)`. Avoid calling MessageList writes from PSI `runWriteAction{}`; if unavoidable, do not perform UI/IO and keep operations to `setAt`/`addMessage` only.
- Do not bounce to the EDT just to read messages. Use `snapshot()` directly; never use `synchronized(messageList)`.
- Do not hold any external lock while calling MessageList methods. Prefer the flow: `snapshot()` -> work off-lock -> `setAt`.

#### Safe patterns (examples)

- Read + UI update
```kotlin
val ml = MessageList.getInstance(project)
val latest = ml.snapshot().lastOrNull()
ApplicationManager.getApplication().invokeLater {
    if (!project.isDisposed) ui.update(latest)
}
```

- IO + update
```kotlin
val ml = MessageList.getInstance(project)
val toDelete = ml.snapshot()
    .flatMap { it.mentionedFiles }
    .filter { it.shouldDelete() }
ApplicationManager.getApplication().executeOnPooledThread {
    deleteFiles(toDelete)
    val idx = ml.indexOfLast { it.role == MessageRole.USER }
    ml.getOrNull(idx)?.let { old ->
        ml.setAt(idx, old.copy(mentionedFiles = old.mentionedFiles - toDelete.toSet()))
    }
}
```

- Immutable update
```kotlin
val ml = MessageList.getInstance(project)
val idx = ml.indexOfLast { it.role == MessageRole.USER }
ml.getOrNull(idx)?.let { old ->
    val updated = old.copy(annotations = (old.annotations ?: Annotations()).copy(x = y))
    ml.setAt(idx, updated)
}
```

## Verification
You do not need to run compileKotlin to verify the project, checking for errors is sufficient.