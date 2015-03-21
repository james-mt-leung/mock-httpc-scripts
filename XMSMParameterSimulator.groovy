package dist

import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.log4j.Logger
import org.simpleframework.http.Request

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RequestHandler {
    private static final Logger logger = Logger.getLogger(RequestHandler.class)
    private static final int THREADS = 10
    private static final ScheduledExecutorService pool = Executors.newScheduledThreadPool(THREADS)

    Map handleRequest(Request request) {
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}");
        def eventId = request.form.find { key, value -> key.toLowerCase() == "event_id" }?.value
        def orgId = request.form.find { key, value -> key.toLowerCase() == "org_id" }?.value
        Map requestInfo = [note: "{eventId:${eventId}, orgId:${orgId}}",
                url: "http://localhost:8888/callbacks/openmarket/api-deliveryReceipt",
                ticketId: "${eventId} - ${orgId}"]

        if (requestInfo.eventId && requestInfo.eventId) {
            logger.debug("Will schedule response to orgId{requestInfo.url} in 10s")
            pool.schedule(new ResponseHandler(requestInfo), 10, TimeUnit.SECONDS)
        }

        return [HttpStatus: 200]
    }
}

class ResponseHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(ResponseHandler.class)
    static HttpClient client = null;
    private def hostName = "localhost"
    private def hostPort = 8888
    final Map responseMap

    ResponseHandler(Map responseInfo) {
        this.responseMap = responseInfo
    }

    @Override
    void run() {
        def postBody = """
            <deliveryReceipt note="${responseMap.note}" ticketId="${responseMap.ticketId}">
                <response description="Message successfully sent to carrier." code="0"/>
                <message>
                    <state description="Delivered to carrier" id="3"/>
                    <reason description="Message successfully sent to carrier." code="0"/>
                </message>
            </deliveryReceipt>
        """

        StringEntity requestEntity = new StringEntity(
                postBody,
                "application/json",
                "UTF-8");

        HttpPost httpPost = new HttpPost(responseMap.url);
        try {
            httpPost.setEntity(requestEntity)
            HttpResponse response = getClient().execute(httpPost)
            logger.info("PostMethod ${responseMap.url} return code: ${response.statusLine.statusCode}.")
        } catch (Exception e) {
            logger.error("PostMethod ${responseMap.url}.", e)
            e.printStackTrace()
        } finally {
            httpPost.releaseConnection()
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


