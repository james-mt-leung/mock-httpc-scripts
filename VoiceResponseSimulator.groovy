package dist

import groovyx.gpars.actor.DefaultActor
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.protocol.HttpContext
import org.apache.log4j.Logger
import org.simpleframework.http.Request

import java.util.concurrent.ConcurrentLinkedQueue

enum JobState {
    INIT(Constants.HOST_URL+"pressAnyDigit", [waitAfterPrompt: "5", promptN : 1]),
    STARTED(Constants.HOST_URL+"reportHumanDetected"),
    DETECTED(Constants.HOST_URL+"content", [isVoicemail: "true", ttsRate: "1s", isPlayMsgOnly: "true"]),
    DELIVERED(Constants.HOST_URL+"respond", [choice: "2"]),
    FAILED(Constants.HOST_URL+"errorExit"),
    RESPONDED(Constants.HOST_URL+"callSuccess"),
    SUCCEEDED(Constants.HOST_URL+"goodbye"),
    ENDED(null);

    JobState(String baseUrl, Map parameters) {
        logger.debug("baseUrl ${baseUrl}; parameters=${parameters}")
        this.baseUrl = baseUrl;
        this.parameters = parameters;
    }

    JobState(String baseUrl) {
        this(baseUrl, [:])
    }

    String getNextUrl(def eventId) {
        String rv = "${baseUrl}?" + parameters.collect {k, v -> "${k}=${v}"}.join("&")
        return parameters.isEmpty() ? rv + "eventId=${eventId}" : rv + "&eventId=${eventId}"
    }

    Logger logger = Logger.getLogger(JobState.class)
    String baseUrl;
    Map parameters;
}

enum JobStatus {
    FAILED, SUCCESS
}

class Constants {
    static String HOST_URL = "http://localhost:8888/apiservice/voxeoapi/"
    static HttpClient client

    static HttpClient createClient() {
        if (client) {
            return client
        }

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(200);
        // Increase default max connection per route to 20
        cm.setDefaultMaxPerRoute(20);
        client = HttpClientBuilder.create().setConnectionManager(cm).build()
    }
}

class Poster extends DefaultActor {
    private static final Logger logger = Logger.getLogger(Poster.class)
    private final HttpClient client

    Poster() {
        client = Constants.createClient()
    }

    void act() {
        loop {
            react { String url ->
                HttpGet method = new HttpGet(url)
                try {
                    HttpResponse response = client.execute(method, (HttpContext)null)
                    int code = response.statusLine.statusCode
                    logger.info("GetMethod ${url} return code = [${code}]; content = [${response?.entity?.content?.toString()}].")
                    reply code >= 200 && code < 300 ? JobStatus.SUCCESS : JobStatus.FAILED
                } catch (Exception e) {
                    logger.error("GetMethod failed ${url}.", e)
                    reply JobStatus.FAILED
                } finally {
                    method.releaseConnection()
                }
            }
        }
    }
}

class RequestHandler extends DefaultActor {
    private static final Logger logger = Logger.getLogger(RequestHandler.class)

    final Poster server = new Poster()
    final Queue<String> jobs = [] as ConcurrentLinkedQueue<String>
    private JobState state = JobState.INIT
    Request request
    def eventId

    RequestHandler(Request request) {
        this.request = request
        eventId = request.form.find { key, value -> key.toLowerCase() == "event_id" }?.value
        logger.info("Request eventId: ${eventId}")
        logger.info("Request received for: ${request.form.collect { key, value -> "[${key} : ${value}]" }.join(" ")}")
        server.start();
        start();
    }

    Map handleRequest() {
        if (eventId) {
            jobs.add(state.getNextUrl(eventId))
        }

        return [HttpStatus: 200]
    }

    void act() {
        loop {
            def url = jobs.poll()
            if (url) {
                logger.info("Next Request: ${url}")
                server.send url
                react { rv ->
                    switch (rv) {
                        case JobStatus.FAILED:
                            if (state == JobState.FAILED) {
                                logger.info("Done ${eventId}")
                                terminate();
                            } else {
                                moveToFailed(url)
                            }
                            break;

                        case JobStatus.SUCCESS:
                            if (state == JobState.FAILED) {
                                logger.info("Done ${eventId}")
                                terminate();
                            } else {
                                moveToNextState()
                                logger.info("State = ${state}")
                                if ([JobState.ENDED].contains(state)) {
                                    logger.info("Done ${eventId}")
                                    terminate();
                                } else {
                                    logger.info("Request succeeded, will send ${state.getNextUrl(eventId)}")
                                    jobs.add(state.getNextUrl(eventId))
                                }
                            }

                            break
                    }
                }
            }
        }
    }

    void moveToNextState() {
        List chain = Arrays.asList(JobState.INIT, JobState.STARTED, JobState.DETECTED, JobState.DELIVERED, JobState.RESPONDED, JobState.SUCCEEDED, JobState.ENDED);
        int pos = chain.indexOf(state);
        if (pos != chain.last()) {
            state = chain.get(pos + 1)
        }
    }

    void moveToFailed(String url) {
        state = JobState.FAILED
        logger.error("Request failed (${url}), will send ${state.getNextUrl(eventId)}")
        jobs.add(state.getNextUrl(eventId))
    }
}

/**
 * return a map, including an entry for HttpStatus
 */
Map handleRequest(Request request) {
    return new RequestHandler(request).handleRequest()
}
