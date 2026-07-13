package io.github.intisy.ai.js;

import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE decisive piece of Phase 2 Task 5: a shared {@link HttpClient} (blocking-shaped —
 * {@code send(HttpRequest): HttpResponse}) whose implementation is actually a JS-provided
 * async function (a {@code fetch}-backed {@code (requestJson: string) => Promise<string>} in
 * production; a mocked one in the test harness), bridged via TeaVM's {@code @Async} native
 * method + {@link AsyncCallback} mechanism.
 *
 * <p>Mechanism: {@link #send} looks synchronous to every caller up the shared call graph
 * (Router.routeJson -&gt; ProxyHandler.handle -&gt; this.send), but internally suspends on
 * {@link #awaitSend}, a native method marked {@code @Async}. TeaVM's whole-program CPS
 * transform propagates "this call graph suspends" all the way up to whichever entrypoint
 * triggered it — in {@link AiJavaJs#routeJsonAsync} that entrypoint runs inside
 * {@code JSPromise.callAsync}, so the suspend/resume surfaces to JS as a normal
 * {@code Promise} that resolves once the JS side's fetch-backed send() resolves.
 */
public final class JsHttpClientBridge implements HttpClient {

    /** JS-provided async HTTP transport: {@code (requestJson: string) => Promise<string>}.
     *  The request/response are plain JSON strings (mirrors shared's own {@code routeJson}
     *  JSON boundary) — no per-field JSO overlay types needed for headers/body/etc.
     *
     *  <p>Uses {@link JSString}, not plain {@code String}, because TeaVM's automatic
     *  String&lt;-&gt;native-JS-string conversion only fires at a DECLARED (non-generic)
     *  JSBody/JSMethod/JSExport boundary. A value flowing through a generic JS-facing functor
     *  (like {@code JSPromise<T>}'s {@code JSMapping<T,V>}/{@code JSConsumer<T>} callbacks) is
     *  type-erased at that call site, so no wrap/unwrap happens and a raw native JS string
     *  leaks straight into Java code expecting a {@code jl_String} wrapper (methods like
     *  {@code length()}/{@code charAt()} then throw, reading undefined internal fields).
     *  {@link JSString} needs no such wrapping — it directly overlays the native JS string —
     *  so routing values through it at every generic boundary sidesteps the gap entirely;
     *  {@code String} conversion happens only at the very edges via
     *  {@code JSString.valueOf}/{@code .stringValue()}. */
    @JSFunctor
    public interface JsHttpSend extends JSObject {
        JSPromise<JSString> send(JSString requestJson);
    }

    private final JsHttpSend jsSend;
    private final JsonCodec json;

    public JsHttpClientBridge(JsHttpSend jsSend, JsonCodec json) {
        this.jsSend = jsSend;
        this.json = json;
    }

    @Override
    public HttpResponse send(HttpRequest req) {
        Map<String, Object> reqMap = new LinkedHashMap<>();
        reqMap.put("method", req.method != null ? req.method : "GET");
        reqMap.put("url", req.url != null ? req.url : "/");
        reqMap.put("headers", req.headers != null ? req.headers : new LinkedHashMap<String, String>());
        reqMap.put("body", req.body != null ? req.body : "");
        String reqJson = json.stringify(reqMap);

        String respJson = awaitSend(jsSend, reqJson); // <-- suspends here; resumes once JS's Promise settles

        return parseResponse(respJson);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse parseResponse(String respJson) {
        HttpResponse resp = new HttpResponse();
        resp.status = 502;
        resp.headers = new LinkedHashMap<>();
        resp.body = "";
        Object parsed = json.parse(respJson);
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            Object status = m.get("status");
            if (status instanceof Number) resp.status = ((Number) status).intValue();
            Object body = m.get("body");
            if (body instanceof String) resp.body = (String) body;
            Object headers = m.get("headers");
            if (headers instanceof Map) {
                Map<String, String> h = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<Object, Object>) headers).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        h.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                resp.headers = h;
            }
        }
        return resp;
    }

    // -- @Async bridge ------------------------------------------------------------

    /** Blocking-looking native entrypoint; TeaVM's async transform makes every caller of this
     *  method (transitively) suspend/resume instead of actually blocking a JS thread. */
    @Async
    private static native String awaitSend(JsHttpSend fn, String reqJson);

    // Companion method: same name, void return, trailing AsyncCallback<T> — this is the exact
    // shape TeaVM's async codegen looks for to pair with the @Async native declaration above.
    // `value`/`error` below are JSString/Object coming straight from the JS Promise's own
    // resolve/reject call — .stringValue() is the explicit, unambiguous String conversion
    // (see the JsHttpSend javadoc for why this can't be a plain generic String parameter).
    private static void awaitSend(JsHttpSend fn, String reqJson, AsyncCallback<String> callback) {
        fn.send(JSString.valueOf(reqJson)).then(
                value -> {
                    callback.complete(value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("upstream fetch rejected: " + error));
                    return null;
                });
    }
}
