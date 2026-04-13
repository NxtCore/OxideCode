# Entities Subsystem

PSI-based entity extraction (classes, functions, properties) across multiple languages. Optional SQLite cache for persistence.

## Architecture

```
entities/
  EntityUtils.kt       → Orchestration: getAllEntities, getEntitiesWithoutReferences
  KtEntityUtils.kt     → Kotlin extraction (reflection for optional plugin)
  JavaEntityUtils.kt   → Java extraction
  PyEntityUtils.kt     → Python extraction (reflection)
  TSEntityUtils.kt     → TypeScript/JavaScript extraction (reflection)
  CppEntityUtils.kt    → C++ extraction (reflection, CLion only)
  RubyEntityUtils.kt   → Ruby extraction (not wired, commented out)
  EntitiesCache.kt     → SQLite persistence (@Deprecated but still used)

utils/
  DatabaseOperationQueue.kt  → Two serialized DB queues per project
  ReflectionUtils.kt         → tryLoadClass, tryMethod for optional plugins
```

## Key Concepts

### PSI Threading Rules
**Always use**:
```kotlin
ReadAction.nonBlocking { ... }
  .inSmartMode(project)
  .submit(boundedExecutor)
```

This ensures:
- Read action for PSI access
- Smart mode for index readiness (avoids `IndexNotReadyException`)
- Background thread (never EDT)

`getEntitiesWithoutReferences()` calls `.get()` and asserts `SlowOperations.assertSlowOperationsAreAllowed()`. Never call from EDT.

### Reflection for Optional Plugins
Language PSI classes loaded via reflection to avoid `NoClassDefFoundError`:

```kotlin
// KtEntityUtils.kt
private val ktClassClass = tryLoadClass("org.jetbrains.kotlin.psi.KtClass")
private val isEnumMethod = ktClassClass?.let { tryMethod(it, "isEnum") }

fun isKotlinFile(psiFile: PsiFile): Boolean =
  ktFileClass?.isInstance(psiFile) == true
```

**Pattern**: Load class → check `!= null` → check `isInstance()` → invoke methods.

### DB Threading Model
`DatabaseOperationQueue` runs two dedicated workers:
- `ENTITY` queue → `"EntityDB-Worker"` thread
- `FILE` queue → `"FileDB-Worker"` thread

**Allowed on DB threads**: JDBC calls, ResultSet mapping, cache metadata updates

**Not allowed on DB threads**: PSI access, `ReadAction`, index queries

**Current bug**: `EntitiesCache.updateCacheForFile()` calls `getEntitiesWithoutReferences()` on DB thread. Should refactor to: PSI work → then enqueue DB write.

### Performance Cutoffs

| Cutoff | Value | Effect |
|--------|-------|--------|
| `TOO_MANY_LINES` | 5000 | Skip file in `getAllEntities` |
| `MAX_FILES_FOR_INDEXING` | 15000 | Disable cache entirely |

Bounded executors:
- `"Sweep.Entities"`: 5 threads
- `"Sweep.FindReferences"`: 5 threads
- `"Sweep.EntitiesWithoutReferences"`: 1 thread (serialized)

### Entity Types

| EntityType | Languages |
|------------|-----------|
| `CLASS` | All |
| `ENUM_CLASS` | All (detected via `isEnum()` or superclass heuristic) |
| `FUNCTION` | All (top-level only for Python) |
| `PROPERTY` | Kotlin, Python (top-level), TS/JS (file-scope), C++ |
| `OBJECT` | Kotlin only |

### Reference Search
Uses `ReferencesSearch.search()` over project scope, filtered to `ProjectFilesCache.contains(path)`.

## Adding Language Support

1. Create `*EntityUtils.kt` with reflection loading
2. Implement `is*Available()` and `is*File()` guards
3. Implement `get*EntitiesWithoutReferences()` returning `List<EntityInfo>`
4. Add dispatch in `EntityUtils.getAllEntities()`
5. Export from entities package
