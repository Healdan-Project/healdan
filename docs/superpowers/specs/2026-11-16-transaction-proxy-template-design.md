# Lookups by type that can fail at once: design

- **Date:** 2026-11-16
- **Status:** proposed (Claude, 2026-11-16), as one small upstream edit and a test. The owner decides on the pull
  request.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. What broke

On 2026-11-14 the Docker image workflow's `Test (arm64)` job failed in step 9 (run 43602076929): the User app's
"a document's own page" spec, in the dark theme in Chromium, found "Something went wrong." where the document should
be. `GET /api/v1/documents/{n}` had answered 500. The server's log named the cause:

```
java.lang.NullPointerException: Cannot invoke "java.util.Set.add(Object)" because "this.suppressedExceptions" is null
  at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.onSuppressedException(DefaultSingletonBeanRegistry.java:476)
  at org.springframework.beans.factory.support.AbstractBeanFactory.getTypeForFactoryBean(AbstractBeanFactory.java:1775)
  ...
  at com.logicaldoc.util.spring.Context.get(Context.java:60)
  at com.logicaldoc.healdan.matter.MatterDAO.get(MatterDAO.java:21)
```

Nothing about the document or the spec mattered: any request that looks a bean up by type could have failed the
same way.

## 2. The cause

Two things together.

- **A bean definition that can't be made.** The webapp's `context.xml` declares `applicationBaseTransactionProxy`, a
  `TransactionProxyFactoryBean` with no target, as a template other definitions could name as their parent. None
  does. It's `lazy-init="true"` but not abstract, so Spring treats it as a real bean that it makes on first use.
  `Context.get(Type.class)` asks Spring for every bean of that type, and to learn what a `FactoryBean` makes, Spring
  makes any that isn't made yet. This one fails ("Property 'target' is required"). Spring then removes it with
  `destroySingleton`, which empties its cache of bean names by type, so the next lookup of any type misses the cache
  and makes it again. Every lookup by type that missed the cache made and failed it.
- **A race in Spring 6.2.** Spring keeps each such failure in `suppressedExceptions`, a set that
  `DefaultSingletonBeanRegistry.getSingleton` creates while it makes a bean and sets to null when it's done.
  `onSuppressedException` checks the set isn't null, then adds to it. Spring 6.1 did both under the lock that
  `getSingleton` holds; 6.2's lenient locking dropped that lock there, and Spring's main branch still checks and adds
  without it. A lookup that failed to make the template could check the set while another thread's `getSingleton`
  held it, then add after that thread had set it to null.

## 3. The change

**`applicationBaseTransactionProxy` is abstract.** (call) Spring never makes an abstract definition, so lookups by type
skip it: no failure, nothing suppressed, and the cache of names by type stays filled. It stays a template a definition
can name as its parent, which is all it was ever for. Nothing refers to it, in the webapp or any plugin.

`healdan-core`'s test `context.xml`, Healdan's copy of LogicalDOC's, which `healdan-core`'s, `healdan-api`'s and
`healdan-storage`'s tests run on, gets the same change, so their concurrency tests (`OncePerMatterConcurrencyTest`,
`DocNumberConcurrencyTest`) can't meet the race either. The upstream modules' test contexts keep theirs.

Spring isn't changed or upgraded: the race needs a lookup that fails, which no definition makes after this change.

## 4. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-webapp/src/main/resources/context.xml` | `applicationBaseTransactionProxy` is `abstract="true"`, with a `Healdan:` note in its comment | A lookup by type never makes and fails it, so two at once can't meet Spring 6.2's race (§2) |

## 5. Tests

`TransactionProxyTemplateTest` (in `logicaldoc-webapp`, fork-owned, in `UPSTREAM_TESTS`) builds a bean factory from the
webapp's own definition of the template, read from `src/main/resources/context.xml`, with a transaction manager and
four plain beans, its configuration frozen as the application context leaves it:

- two lookups by type make Spring suppress no failure. Before the change it suppressed one for each;
- eight threads that each look the four types up 200,000 times all succeed. Before the change this threw the smoke
  stack's `NullPointerException`, to the word, in under a second. After it, the run takes about half a second.

## 6. Later

- **Report both upstream.** (call) To LogicalDOC: the template should be abstract. To Spring: `onSuppressedException`
  checks and adds without the lock `getSingleton` holds, since 6.2.
