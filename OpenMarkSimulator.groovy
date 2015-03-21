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

    private static final boolean enableSegments = true;
    private static final int segmentSplitLength = 90;

    Map handleRequest(Request request) {
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}");
        def text = request.form.find { key, value -> key == "text" }?.value

        // Determine number of segments and send a delivery receipt
        def eventId = request.form.find { key, value -> key == "event_id" }?.value
        def orgId = request.form.find { key, value -> key == "org_id" }?.value
        Map requestInfo = [note    : "{eventId:${eventId}, orgId:${orgId}}",
                           url     : "http://" + HOST + ":" + PORT + DELIVERY_RECEIPT_PATH,
                           ticketId: "${eventId} - ${orgId}"]


        int numberOfSegments;
        if (enableSegments) {
            numberOfSegments = (int) Math.ceil((double) text.length() / segmentSplitLength);
        }

        if (eventId && orgId) {
            logger.info("Will schedule delivery receipt  to ${requestInfo.url} in 10s")
            pool.schedule(new DeliveryReceiptHandler(requestInfo, numberOfSegments), 10, TimeUnit.SECONDS)
        }

        def from = request.form.find { key, value -> key == "from" }?.value
        def to = request.form.find { key, value -> key == "to" }?.value
        int code = getResponseCode(text)
        if (code >= 0) {
            def url = "http://" + HOST + ":" + PORT + RESPONSE_PATH
            logger.info("Will schedule response to ${url} in 30s")
            pool.schedule(new ResponseHandler(url, from, to, code), 30, TimeUnit.SECONDS)
        }

        return [HttpStatus: 200]
    }

    int getResponseCode(String content) {
        logger.info("Request message: '${content}'")
        if (!content) {
            return -1
        }

        // for communication api; add 'reply' to the end of your message.
        def matcher = content =~ '.*reply\\s*(.*).*'
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

class DeliveryReceiptHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(DeliveryReceiptHandler.class)
    private static HttpClient client = null;
    private final Map responseMap;
    private final int numberOfSegments;

    DeliveryReceiptHandler(Map responseInfo, int numberOfSegments) {
        this.responseMap = responseInfo
        this.numberOfSegments = numberOfSegments;
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
        """;
        if (numberOfSegments && numberOfSegments > 1) {
            for (int i = 1; i <= numberOfSegments; i++) {
                postBody = """
                    <deliveryReceipt note="${responseMap.note}" ticketId="${responseMap.ticketId}">
                        <response description="Message successfully sent to carrier." code="0"/>
                        <message segmentNumber="${i}">
                            <state description="Delivered to carrier" id="3"/>
                            <reason description="Message successfully sent to carrier." code="0"/>
                        </message>
                    </deliveryReceipt>
                """
                doPost(postBody);
            }
        } else {
            doPost(postBody);
        }
    }

    void doPost(String postBody) {
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

class ResponseHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(ResponseHandler.class)
    static HttpClient client = null;
    final def url
    final def from
    final def to
    final String code

    ResponseHandler(def url, def from, def to, int code) {
        this.url = url
        this.from = from
        this.to = to
        this.code = toHex(String.valueOf(code));
    }

    @Override
    void run() {
        String postBody = """
            xml=
            %3Crequest+version%3D%223.0%22+protocol%3D%22wmp%22+type%3D%22deliver%22%3E
                %3Caccount+id%3D%22000-000-110-13021%22%2F%3E
                %3Cdestination+ton%3D%223%22+address%3D%22${from}%22%2F%3E
                %3Csource+carrier%3D%2234%22+ton%3D%220%22+address%3D%22%2B${to}%22%2F%3E
                %3Coption+datacoding%3D%227bit%22+%2F%3E
                %3Cmessage+udhi%3D%22false%22+data%3D%22${code}%22%2F%3E
                %3Cticket+id%3D%224514U-05054-18168-243MR%22%2F%3E
            %3C%2Frequest%3E
        """;

        doPost(postBody);
    }

    void doPost(String postBody) {
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

    public String toHex(String arg) {
        return String.format("%040x", new BigInteger(1, arg.getBytes("US-ASCII")));
    }
}

/**
 * return a map, including an entry for HttpStatus
 */
Map handleRequest(Request request) {
    return new RequestHandler().handleRequest(request)
}
