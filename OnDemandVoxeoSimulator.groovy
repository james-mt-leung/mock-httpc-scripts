package dist

import groovyx.gpars.actor.DefaultActor
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.log4j.Logger
import org.simpleframework.http.Request

import java.util.concurrent.ConcurrentLinkedQueue

class Poster extends DefaultActor {
    private static final Logger logger = Logger.getLogger(Poster.class)
    private static HttpClient client

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

    void act() {
        loop {
            react { String url ->
                HttpGet method = new HttpGet(url)
                try {
                    HttpResponse response = getClient().execute(method)
                    logger.info("PostMethod ${url} return code: ${response.statusLine.statusCode}.")
                    reply response.statusLine.statusCode >= 200 && response.statusLine.statusCode < 300 ? "SUCCESS" : "FAILED"
                } catch (Exception e) {
                    logger.error("GetMethod failed ${url}.", e)
                    reply "FAILED"
                } finally {
                    method.releaseConnection()
                }
            }
        }
    }
}

class RequestHandler extends DefaultActor {
    private static final Logger logger = Logger.getLogger(RequestHandler.class)
    //private static String HOST_URL = "https://ws-voxeo:eU1GisWsuS3r@eu1.xmatters.com/voicexml/rest/voxeo/goodbye?callSid=abc&userPreferredLang=English&callFinished=true&called=9999999"
    //private static String HOST_URL = "http://admin:complex@localhost:8888/voicexml/rest/voxeo/goodbye?callSid=abc&userPreferredLang=English&callFinished=false&called=9999999"
    //private static String HOST_URL = "http://admin:complex@localhost:8888/voicexml/rest/voxeo/callReachPerson?callSid=abc&called=9999999"
    //private static String HOST_URL = "http://admin:complex@localhost:8888/voicexml/rest/voxeo/noSalutation?callSid=abc&called=9999999&userPreferredLang=English&callFinished=true&isValidationMessage=true"
    private static String HOST_URL = "http://ws-voxeo:eU1GisWsuS3r/voicexml/rest/voxeo/reportVoicemailOption?callSid=abc&callUid=abc&voicemailOptions=CALLBACK"

    final Poster server = new Poster()
    final Queue<String> jobs = [] as ConcurrentLinkedQueue<String>
    Request request

    RequestHandler(Request request) {
        this.request = request
    }

    Map handleRequest() {
        def ntfnId = request.form.find { key, value -> key.toLowerCase() == "ntfn_id" }?.value
        def nodeId = request.form.find { key, value -> key.toLowerCase() == "node_id" }?.value
        logger.info("Request ntfnId: ${ntfnId}")
        logger.info("Request nodeId: ${nodeId}")
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}")
        if (!ntfnId) {
          return;
        }

        if (ntfnId) {
            jobs.add(HOST_URL + "&ntfnId=${ntfnId}&nodeId=${nodeId}")
        }
        server.start();
        start();

        def responseBody = """
          <VoxeoProxyResponse code="200" message="ok"/>
        """
        return [HttpStatus: 200, responseText: "success"]
    }

    void act() {
        loop {
            Thread.currentThread().sleep(2000)
            def url = jobs.poll()
            if (url) {
                logger.info("Next Request: ${url}")
                server.send url
                react {                             
			terminate();
                }
            }
        }
    }

}

/**
 * return a map, including an entry for HttpStatus
 */
Map handleRequest(Request request) {
    return new RequestHandler(request).handleRequest()
}

