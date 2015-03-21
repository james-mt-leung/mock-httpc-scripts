package dist

import org.simpleframework.http.Request

/**
 * This is the method XMattersHttpSimulator will invoke after receiving a Request.
 * You can optionally return a simulated Http code using 'HttpStatus' as the key.
 */
Map handleRequest(Request request) {
    // get all request attribute map
    Map attributes = request.attributes
    println(attributes)

    // get the client address
    InetSocketAddress clientAddress = request.clientAddress
    println(clientAddress)

    // get the request content
    String content = request.content
    println(content)

    // get the request content
    println(request.getParameter("paraName"))

    // get a request attribute given a key of type Object
    Object attr = "abc"
    println("get ${attr}: ${request.getAttribute(attr)}")

    // get a list of cookies
    List<org.simpleframework.http.Cookie> cookies = request.cookies
    println("cookies: ${cookies}")

    // get the content type
    org.simpleframework.http.ContentType contentType = request.contentType
    println("contentType.charset=${contentType?.charset}")

    int rc = 200
    return [HttpStatus: rc]
}
