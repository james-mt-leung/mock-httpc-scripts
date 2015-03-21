import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.protocol.HttpContext
import org.apache.log4j.Logger
import org.ccil.cowan.tagsoup.Parser
import org.simpleframework.http.Request

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RequestHandler {
    private static final Logger logger = Logger.getLogger(RequestHandler.class)
    private static final int THREADS = 10
    private static final ScheduledExecutorService pool = Executors.newScheduledThreadPool(THREADS)
    private static final Map contentByEventId = Collections.synchronizedMap(new LinkedHashMap());

    Map handleRequest(Request request) {
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}")
        def content = request.form.find { key, value -> key.toLowerCase() == "htmlmessage" }?.value
        def eventId = request.form.find { key, value -> key.toLowerCase() == "event_id" }?.value

        while (contentByEventId.size() > 1000) {
            contentByEventId.remove(contentByEventId.keySet()[0])
        }

        if (eventId && !content) {
            def oldest = contentByEventId.isEmpty() ? "n/a" : contentByEventId.keySet()[0]
            logger.info("Cached # of events ${contentByEventId.size()}; oldest: ${oldest}")
            return [HttpStatus: 200, responseText: "${contentByEventId.get(eventId)}"]
        }

        if (eventId && content) {
            contentByEventId.put(eventId, content)
        }

        def linkInfo = parseRequest(content)
        if (linkInfo) {
            logger.debug("Will schedule response to ${linkInfo.url} in 30s")
            pool.schedule(new ResponseHandler(linkInfo), 30, TimeUnit.SECONDS)
        }

        return [HttpStatus: 200, responseText: "success"]
    }

    Map parseRequest(String content) {
        logger.debug("Request htmlMessage: [${content}]")
        if (!content) {
            return [:]
        }

        def parser = new XmlSlurper(new Parser())
        def html = parser.parseText(content)
        def responseSection = html.body.ol?.find {
            it.'@title' == "Response Choices"
        }

        logger.debug("Found responseSection: ${responseSection}");

        def links = responseSection?.li?.a
        logger.info("Found response links: ${links.@href}")
        if (links.size() > 0) {
            def link = links[(int) System.currentTimeMillis() % links.size()]
            return [url: link.@href.text()]
        }

        return [:]
    }
}

class ResponseHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(ResponseHandler.class)
    static HttpClient client = null;
    final Map responseMap

    ResponseHandler(Map responseInfo) {
        this.responseMap = responseInfo
    }

    @Override
    void run() {
        HttpGet method = new HttpGet(responseMap.url);
        try {
            HttpResponse response = getClient().execute(method, (HttpContext) null)
            int code = response.statusLine.statusCode
            logger.info("GetMethod ${responseMap.url} return code: ${code}.")
        } catch (Exception e) {
            logger.error("GetMethod ${responseMap.url}.", e)
            e.printStackTrace()
        } finally {
            method.releaseConnection()
        }
    }

    static HttpClient getClient() {
        if (!client) {
            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            cm.setMaxTotal(200);
            // Increase default max connection per route to 20
            cm.setDefaultMaxPerRoute(20);

            client = HttpClientBuilder.create().setConnectionManager(cm).build()
        }

        return client
    }
}

/**
 * return a map, including an entry for HttpStatus
 */
Map handleRequest(Request request) {
    return new RequestHandler().handleRequest(request)
}
