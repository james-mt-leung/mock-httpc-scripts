package dist

import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.protocol.HttpContext
import org.apache.log4j.Logger
import org.simpleframework.http.Request

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RequestHandler {
    private static final Logger logger = Logger.getLogger(RequestHandler.class)
    private static final int THREADS = 10
    private static final ScheduledExecutorService pool = Executors.newScheduledThreadPool(THREADS)
    private static final int apiId = 1
    private static final int shortCode = 123456
    private static final String HOST_URL = "http://localhost:8888"

    Map handleRequest(Request request) {
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}");
        def code = getResponseCode(request.form.find { key, value -> key.toLowerCase() == "text" }?.value)
        if (code >= 0) {
            def from = request.form.find { key, value -> key.toLowerCase() == "to" }?.value
            def url = "${HOST_URL}/services/cns-sms?text=${code}&from=${from}&destination=${shortCode}"
            logger.info("Will schedule response to ${url} in 30s")
            pool.schedule(new ResponseHandler(url), 30, TimeUnit.SECONDS)
        }

        return [HttpStatus: 200]
    }

    int getResponseCode(String content) {
        logger.info("Request message: '${content}'")
        if (!content) {
            return -1
        }

        // add 'reply' to the end of your message.
        def matcher = content =~ '(?i)(?s).*reply\\s*(.*).*'
        def found = []
        if (matcher.matches()) {
            def replyText = matcher.group(1)
            found = replyText.findAll("(\\d+) to") { group ->
                group[1] as int
            }
        }

        return found ? found[(int) System.currentTimeMillis() % found.size()] : -1
    }
}

class ResponseHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(ResponseHandler.class)
    static HttpClient client = null;
    final def url

    ResponseHandler(def url) {
        this.url = url
    }

    @Override
    void run() {
        HttpGet method = new HttpGet(url);
        try {
            HttpResponse response = getClient().execute(method, (HttpContext)null)
            logger.info("GetMethod ${url} return code: ${response.statusLine.statusCode}.")
        } catch (Exception e) {
            logger.error("GetMethod ${url}.", e)
            e.printStackTrace()
        } finally {
            method.releaseConnection()
        }
    }

    static HttpClient getClient() {
        if (!client) {
            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            // Increase max total connection to 200
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
