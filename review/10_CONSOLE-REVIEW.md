# `mqtt-sn-gateway-console` — Code Review

Scope: `mqtt-sn-gateway-console/src/main/java`. The operator-facing HTTP console + shipped distribution. Lens: web security (authn/authz, transport, input handling, output safety), and the usual SOLID / thread-safety pass. File:line references are 1-based.

## TL;DR

This is the operator surface — it directly manipulates the gateway's configuration and credentials store. The current state is **dangerously insecure as shipped**:

1. **Default credentials are `admin` / `password`** (`MqttsnConsoleOptions.java:38–39`) and the console is enabled by default on `0.0.0.0:8080` — anyone on the network can take over.
2. **Auth is only required for HTML responses.** `MqttsnStaticWebsiteHandler.getRequiredCredentials` returns credentials only when the requested resource ends in `.html` (line 51). Every AJAX/JSON endpoint — including `ClientAccessHandler.handleHttpPost`, which **adds credentials to the gateway's allowed-clients list** — has no auth at all. This is a textbook auth bypass: the login form protects the login page; the API behind it is open.
3. **Password comparison is non-constant-time** (`AbstractHttpRequestResponseHandler.handleBasicHttpAuthentication:236`: `equals` on user/pass). Timing-attack family, same as core / protection / CMAC reviews.
4. **No HTTPS support in the bootstrap.** `SunHttpServerBootstrap` uses plain `HttpServer.create(...)` — credentials over the wire as base64.
5. **No CSRF protection visible** on any state-changing handler. Authenticated admin's browser can be made to POST to any endpoint via a cross-origin form.
6. **Error responses leak exception messages** to the client (`handleRequest:93`: `writeASCIIResponse(..., SC_INTERNAL_SERVER_ERROR, e.getMessage())`).
7. **Path sanitisation only collapses `//`** — does not filter `..` (`HttpUtils.sanitizePath:44`). Static-resource serving via classpath limits the damage today, but the function name oversells what it does.
8. **`MqttsnConsoleOptions.password` is `public`** (no encapsulation) and `toString` prints it.

If the console must ship in its current form, it **must be disabled by default**. The auth-bypass plus default credentials is the kind of finding that lands on the front page of a security bulletin.

---

## 1. Critical — authentication / authorisation

### 1.1 Default credentials `admin` / `password`, console enabled by default, bound to `0.0.0.0`
`MqttsnConsoleOptions.java:35–39`:
```java
public static final boolean DEFAULT_CONSOLE_ENABLED = true;
public static final int DEFAULT_CONSOLE_PORT = 8080;
public static final String DEFAULT_CONSOLE_HOST_NAME = "0.0.0.0";
public static final String DEFAULT_CONSOLE_USERNAME = "admin";
public static final String DEFAULT_CONSOLE_PASSWORD = "password";
```
Out-of-the-box, the gateway exposes a remote-administration HTTP service on every network interface with well-known credentials. Required changes:
- **Off by default.** `DEFAULT_CONSOLE_ENABLED = false`.
- **Loopback by default.** `127.0.0.1`, not `0.0.0.0`. Operators opt in to wider exposure.
- **No default password.** Require the operator to set one at first boot, or refuse to start.

### 1.2 Auth bypass: API endpoints are unauthenticated
`MqttsnStaticWebsiteHandler.getRequiredCredentials:48–58`:
```java
if (HttpUtils.getFileExtension(request.getHttpRequestUri().getPath()).equals("html")) {
    String userName = options.getUserName();
    ...
    return new UsernamePassword(userName, password, "mqtt-sn-gateway");
}
return null;
```
Only `*.html` requests trigger auth. Every JSON/AJAX endpoint runs through a handler whose `getRequiredCredentials` is the default — `AbstractHttpRequestResponseHandler.java:247–249` returns `null` ("no auth"). This means:

| Endpoint | Auth? |
|---|---|
| `GET /index.html` | yes |
| `POST /access` (`ClientAccessHandler` — adds gateway-side credentials) | **no** |
| `GET /command?_cmd=clear-cache` (`CommandHandler`) | **no** |
| `POST /cloud` (`CloudHandler`) | **no** |
| `GET /config` (`ConfigHandler`) | **no** |
| `GET /dlq` (`DLQHandler`) | **no** |
| every other `*Handler` | **no** unless they override `getRequiredCredentials` (spot-check: none do) |

**Fix**: make `getRequiredCredentials` resolve auth at the framework level (`MqttsnConsoleAjaxRealmHandler` should override and require credentials by default), not per-handler with an opt-in.

### 1.3 Basic-auth password comparison is non-constant-time
`AbstractHttpRequestResponseHandler.handleBasicHttpAuthentication:234–238`:
```java
if (usernamePassword.getUserName().equals(userNamePassword[0])
        && usernamePassword.getPassword().equals(userNamePassword[1])) {
    return true;
}
```
Both `equals` calls short-circuit on the first mismatch. Same timing-attack class as `Security.verifyHMac` (core review §3.1) and CMAC verification (protection review §1.4). Use `MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))` for both fields.

### 1.4 No HTTPS path
`SunHttpServerBootstrap.java:55–60` uses `HttpServer.create(bindAddress, tcpBacklog)`. The JDK ships `HttpsServer` (same package); this code does not wire it in. Until that lands, every console password and every AJAX payload travels in clear over the network.

### 1.5 `handleBasicHttpAuthentication` is brittle on malformed headers
Same method, line 230–235:
```java
value = value.substring(value.lastIndexOf(" ") + 1);    // assumes "Basic <base64>"
value = new String(Base64.getDecoder().decode(value));
String[] userNamePassword = value.split(":");
if (usernamePassword.getUserName().equals(userNamePassword[0]) && ...userNamePassword[1]...) {
```
- No verification that the auth scheme is `Basic` (a `Bearer` token would have its last word base64-decoded).
- `Base64.getDecoder().decode(...)` throws `IllegalArgumentException` on malformed input → caught by the outer `Exception` handler in `handleRequest:88` → 500 with `e.getMessage()` returned to the client (see §3.2).
- `value.split(":")` — passwords containing `:` are truncated. If the password is `admin:secret`, `userNamePassword[1] = "secret"` and the rest is silently dropped; if the password is `pass`, the array has length 1 → `userNamePassword[1]` → `ArrayIndexOutOfBoundsException`.
- No `null` / length check before indexing.

### 1.6 No CSRF protection on state-changing endpoints
`ClientAccessHandler.handleHttpPost:78` and `CloudHandler.handleHttpPost:92` accept JSON bodies and persist configuration. No CSRF token, no SameSite cookie check, no `Origin`/`Referer` validation. Even after §1.2 is fixed (so auth is required), an authenticated admin's browser can be made to issue these POSTs cross-origin.

### 1.7 No rate-limiting / brute-force protection
With a 7-char default password and basic auth, a remote attacker can try ~100 passwords per second. Add lockout / exponential backoff at the auth layer.

---

## 2. High — input handling / output safety

### 2.1 Internal exception messages flow to the client
`AbstractHttpRequestResponseHandler.handleRequest:88–96`:
```java
catch (Exception e) {
    e.printStackTrace();
    logger.error("unhandled error", e);
    try {
        writeASCIIResponse(httpRequestResponse, HttpConstants.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } ...
}
```
The exception message is returned in the 500 body. For Java exceptions that includes class names, file paths, sometimes SQL/connection-string fragments. Replace with a generic `"Internal Server Error"`; keep the detailed log on the server.

### 2.2 `e.printStackTrace()` next to a logger
Same lines. Bypasses logging and dumps to stderr. Use the logger only.

### 2.3 `sanitizePath` only collapses double slashes
`HttpUtils.sanitizePath:44`:
```java
public static String sanitizePath(String resource){
    return resource.replaceAll("//+", "/");
}
```
The name implies normalisation; the implementation does not handle `..`, URL-encoded variants (`%2e%2e`), or backslashes. `StaticFileHandler.handleHttpGet:46–49` feeds this into `getResourceAsStream` which is classpath-bounded (somewhat safer), but anyone who changes the loader to a filesystem-backed one inherits a directory-traversal vulnerability. Either fix the function to actually sanitise, or rename to `collapseSlashes` so the contract is honest.

### 2.4 `HttpUtils.getContextRelativePath` uses `indexOf` (line 41)
```java
return requestUri.substring(requestUri.indexOf(contextPath) + contextPath.length());
```
If `contextPath` appears anywhere in the URI other than the prefix, this slices at the wrong location. Use `startsWith` + `substring(contextPath.length())`.

### 2.5 `getFileExtension` (line 48–53) is case-sensitive and substring-loose
```java
if (requestUri.contains(".")) {
    return requestUri.substring(requestUri.lastIndexOf(".") + 1);
}
```
A path like `/foo.html/../bar` returns `html/../bar` as the extension; combined with §1.2 ("auth required iff ext == html"), an attacker requesting `/legitimate.html/admin-api?stuff` could plausibly slip past the prefix check. Validate via a strict regex (`^[a-zA-Z0-9]{1,8}$`) or by checking the *last path segment*'s extension.

### 2.6 Static initialisers / non-final public statics
`MqttsnConsoleOptions.java:50–51`: `public String userName`, `public String password`, etc. — every option field is `public` and mutable. Anything in the JVM can rewrite the gateway's admin password at runtime.

### 2.7 `MqttsnConsoleOptions.toString()` prints the password
Line 145 (per the grep). Same finding as `MqttsnProtectionOptions.toString()` (protection review §2.2). DEBUG-level logs reveal the admin password.

---

## 3. Medium — lifecycle / concurrency

### 3.1 `SunHttpServerBootstrap.stopServer` awaitTermination is 10 000 *seconds*
`SunHttpServerBootstrap.java:93`:
```java
threadPoolExecutor.awaitTermination(10000, TimeUnit.SECONDS);
```
That's **~2.78 hours**. Almost certainly `10000` was meant to be `MILLISECONDS` (10 s) or the value `10` with `SECONDS`. As-is, a graceful shutdown hangs the JVM for hours if any handler refuses to finish.

### 3.2 `SunHttpServerBootstrap` has no max-request-size / max-header-size
`HttpServer` from `com.sun.net.httpserver` accepts whatever the client sends. A 4 GB POST body to `/access` will be buffered until OOM. Set explicit limits at the handler layer; the SunHttpServer doesn't have them.

### 3.3 `MqttsnConsoleOptions.serverThreads = 2` default
Line 41. Two-thread executor for the entire admin console is fine for trivial polling but will queue under load. Configurable, but the default is restrictive enough that a few slow requests can starve the auth/login page.

### 3.4 `MqttsnConsoleService` and handlers reach into the registry directly
Same `IMqttsnRuntimeRegistry` god-interface pattern flagged across all earlier reviews — the console layer is a worst offender because every handler is now coupled to the gateway runtime. Hard to test individual handlers in isolation.

### 3.5 `CommandHandler.handleHttpGet` switches on a string parameter
`CommandHandler.java:50` — `switch(command) { case "clear-cache": ... }`. A new command means a new case in this switch. Combined with no auth (§1.2), the command surface is "whatever string lands in `_cmd`". Use a typed enum + handler registry.

---

## 4. Medium — SOLID / structure

### 4.1 Custom HTTP stack on top of `com.sun.net.httpserver`
The console invents its own request/response abstraction (`IHttpRequestResponse`, `AbstractHttpRequestResponseHandler`, etc.) wrapping a JDK-internal HTTP server (`com.sun.net.httpserver`). Two consequences:
- Internal package — Sun reserves the right to remove it; future JDK upgrades may break.
- The handcrafted Basic-Auth + handler chain re-implements features that any standard servlet container (Jetty, Undertow) provides correctly. Consider migrating to Jetty embedded.

### 4.2 Handler classes are 40–120 lines of mostly boilerplate
Most `*Handler` classes are: parameter validation, registry lookup, `writeMessageBeanResponse(...)`. A small DSL or a single dispatcher reading annotation metadata would compress this and remove a lot of repeated null-checks.

### 4.3 `MqttsnConsoleAjaxRealmHandler` casts registry to gateway type in constructor
`MqttsnConsoleAjaxRealmHandler.java:38`: `this.registry = (IMqttsnGatewayRuntimeRegistry) registry;`. Same generic-erasure pattern flagged in core/gateway. Inherit a typed registry instead.

### 4.4 `NoCloudServiceImpl` indicates the cloud client may not be wired
`mqtt-sn-gateway-console/src/main/java/org/slj/mqtt/sn/console/NoCloudServiceImpl.java` exists as a stub. Worth confirming whether the cloud connector is actually wired in any shipped distribution; if not, the cloud handlers can be deleted from the console build.

### 4.5 `Html` class is a manual HTML builder
`mqtt-sn-gateway-console/src/main/java/org/slj/mqtt/sn/console/http/Html.java`. Manual HTML construction without an HTML-aware escaper is the standard XSS attack surface. Spot-check the templates that consume user-supplied values (clientId, topic) for proper escaping.

---

## 5. Low — style / micro

- `SunHttpServerBootstrap.java:57`: log format string `"...tcpBacklog={}}"` has a stray `}` at the end.
- `SunHttpServerBootstrap.java:82`: `server.stop(1)` — magic number; second-grain delay. Add a constant + comment.
- `AbstractHttpRequestResponseHandler.handleRequest:80–82` logs the warning twice: `logger.warn(...)` then `logger.error(...)` for the same exception. Pick one severity.
- `MqttsnStaticWebsiteHandler.java:50`: comment `"only protect html resources, else basic auth will be mandated on resources like CSS"` documents the auth bypass as a *feature*. The actual problem (API endpoints unprotected) is not acknowledged.
- `MqttsnConsoleOptions.java:38–51` has six `public static final` constants followed by six matching `public` mutable fields — the redundancy is the smell.
- `MqttsnGatewayMain` (entry point) not reviewed in detail; on next pass verify that it does not enable the console by default in the shipped jar configuration.

---

## Recommended sequencing of fixes

1. **§1.1 — change defaults**: console off, loopback bind, no default password. Single-commit, immediate risk reduction.
2. **§1.2 — require auth at the framework layer**: make `MqttsnConsoleAjaxRealmHandler.getRequiredCredentials` non-null by default. Each handler that genuinely needs to be public opts out explicitly. This closes the bypass before anything else.
3. **§1.3 — `MessageDigest.isEqual` for both username and password comparison**.
4. **§3.1 — fix the `10000 SECONDS` → `10 SECONDS` typo**.
5. **§2.1 — strip exception messages from 500 bodies**.
6. **§1.4 / §1.6 — design an HTTPS + CSRF story**. These are larger pieces of work; until they land, document that the console must run behind an authenticating reverse proxy on localhost.
7. **§2.3 / §2.5 — tighten `sanitizePath` and `getFileExtension`**.
8. **§1.7 — rate-limit basic-auth attempts**.
9. **Then** SOLID work (§4) — most usefully a migration off `com.sun.net.httpserver` to Jetty/Undertow, which brings TLS, request size limits, and a proper auth pipeline for free.
