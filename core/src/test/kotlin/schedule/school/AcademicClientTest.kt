package schedule.school

import org.junit.Assert.*
import org.junit.Test

class AcademicClientTest {
    @Test fun academicSessionExchangesTicketAndUsesOnlyServiceCookies() {
        var exchanges = 0
        var exams = 0
        val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture", "secret") }, SchoolTransport { request ->
            fun response(body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) = SchoolResponse(status, headers, body.toByteArray())
            when {
                request.uri.host == "zjuam.zju.edu.cn" && request.uri.query != null -> {
                    exchanges++
                    assertTrue(request.headers["Cookie"]!!.single().contains("iPlanetDirectoryPro=sso"))
                    assertTrue(request.headers["Cookie"]!!.single().contains("CASTGC=cas-session"))
                    response("", 302, mapOf("Location" to listOf("http://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html?ticket=fixture-ticket")))
                }
                request.uri.host == "zjuam.zju.edu.cn" && request.method == "POST" -> response("", 302, mapOf("Set-Cookie" to listOf("iPlanetDirectoryPro=sso; Path=/; Secure", "CASTGC=cas-session; Path=/cas; Secure")))
                request.uri.path.endsWith("getPubKey") -> response("""{"modulus":"ca1","exponent":"11"}""")
                request.uri.host == "zjuam.zju.edu.cn" -> response("<input name=\"execution\" value=\"token\">")
                request.uri.path.endsWith("login_ssologin.html") -> {
                    assertEquals("https", request.uri.scheme)
                    assertFalse(request.headers.containsKey("Cookie"))
                    response("", 302, mapOf("Set-Cookie" to listOf("JSESSIONID=business; Path=/jwglxt; Secure", "route=node; Path=/; Secure")))
                }
                request.uri.path.endsWith("kscx_cxXsgrksIndex.html") -> {
                    exams++
                    assertEquals("POST", request.method)
                    assertEquals("JSESSIONID=business; route=node", request.headers["Cookie"]!!.single())
                    assertEquals("XMLHttpRequest", request.headers["X-Requested-With"]!!.single())
                    assertNull(request.body)
                    response("""{"items":[]}""")
                }
                else -> error("Unexpected request")
            }
        })
        assertTrue(client.fetchExams().items.isEmpty())
        assertTrue(client.fetchExams().items.isEmpty())
        assertEquals(1, exchanges)
        assertEquals(2, exams)
    }
}
