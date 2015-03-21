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

    private static final String HOST = "xm-api.no-ip.org";
    private static final String PORT = "8888";
    private static final String DELIVERY_RECEIPT_PATH = "/callbacks/openmarket/api-deliveryReceipt";
    private static final String RESPONSE_PATH = "/callbacks/openmarket/api-userResponse";

    private static final int segmentSplitLength = 90;

    Map handleRequest(Request request) {
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}");

        def eventId = request.form.find { key, value -> key == "event_id" }?.value
        def orgId = request.form.find { key, value -> key == "org_id" }?.value
        def message = request.form.find { key, value -> key == "message" }?.value

        def url = "http://" + HOST + ":" + PORT + DELIVERY_RECEIPT_PATH
        logger.info("Will schedule response to ${url} in 0s")
        pool.schedule(new DeliveryReceiptHandler(segmentSplitLength, url, orgId, eventId, message), 1, TimeUnit.MILLISECONDS)

        return [HttpStatus: 200]
    }
}

class DeliveryReceiptHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(DeliveryReceiptHandler.class)
    private static HttpClient client = null;
    final def url
    final def orgId
    final def eventId
    final def segmentSplitLength
    final int numberOfSegments

    DeliveryReceiptHandler(segmentSplitLength, String url, def orgId, def eventId, String message) {
        this.url = url
        this.orgId = orgId
        this.eventId = eventId
        this.segmentSplitLength = segmentSplitLength
        // number of whole parts (ex. rounded up)
        this.numberOfSegments = (message.length() + segmentSplitLength - 1) / segmentSplitLength
    }

    @Override
    void run() {
        def postBody = """
            <deliveryReceipt note="{'orgId': ${orgId}, 'eventId': ${eventId}}" ticketId='1'>
                <response description="Message successfully sent to carrier." code='4'/>
                <message>
                    <state description="Delivered to carrier" id="3"/>
                    <reason description="Message successfully sent to carrier." code="0"/>
                </message>
            </deliveryReceipt>
        """
        if (numberOfSegments > 1) {
            logger.info("Will send a totol of $numberOfSegments delivery receipt")
            for (int i = 1; i <= numberOfSegments; i++) {
                postBody = """
                <deliveryReceipt note="{'orgId': ${orgId}, 'eventId': ${eventId}}" ticketId='1'>
                    <response description="Message successfully sent to carrier." code='4'/>
                    <message segmentNumber="$i">
                        <state description="Delivered to carrier" id="3"/>
                        <reason description="Message successfully sent to carrier." code="0"/>
                    </message>
                </deliveryReceipt>
                """
                doPost(postBody)
            }
        } else {
            doPost(postBody)
        }
    }

    void doPost(String postBody) {
        logger.info("post body ${postBody}")

        StringEntity requestEntity = new StringEntity(
                postBody,
                "application/json",
                "UTF-8");

        HttpPost httpPost = new HttpPost(url);
        try {
            httpPost.setEntity(requestEntity)
            HttpResponse response = getClient().execute(httpPost)
            logger.info("PostMethod ${url} return code: ${response.statusLine.statusCode}.")
        } catch (Exception e) {
            logger.error("PostMethod ${url}.", e)
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
