package schedule.school

import org.junit.Assert.*
import org.junit.Test

class SchoolCredentialTest {
    @Test fun failedLoginDiagnosticContainsResponseEvidenceWithoutSecrets() {
        var report = ""
        val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture-user", "fixture-secret") }, SchoolTransport { request ->
            when {
                request.method == "POST" -> SchoolResponse(200, mapOf("Content-Type" to listOf("text/html")), "<html><script>fixture-secret</script><input value=\"private-token\"><p>认证服务器返回登录表单</p><p>fixture-user fixture-secret private-cookie token=opaque-token</p></html>".toByteArray())
                request.uri.path.endsWith("getPubKey") -> SchoolResponse(200, emptyMap(), """{"modulus":"ca1","exponent":"11"}""".toByteArray())
                else -> SchoolResponse(200, mapOf("Set-Cookie" to listOf("CASID=private-cookie; Path=/; Secure")), "<input name=\"execution\" value=\"private-token\">".toByteArray())
            }
        }, diagnostics = { report = it })
        assertThrows(SchoolException::class.java) { client.verifyCredentials() }
        assertTrue(report.contains("认证服务器返回登录表单"))
        for (secret in listOf("fixture-user", "fixture-secret", "private-cookie", "private-token", "opaque-token")) assertFalse(report.contains(secret))
        assertTrue(report.contains("CASID"))
    }
    @Test fun casHandshakeMatchesElychron141RequestSequenceAndCookieList() {
        var step = 0
        val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture", "A") }, SchoolTransport { request ->
            assertEquals("zjuam.zju.edu.cn", request.uri.host)
            assertFalse(request.headers.containsKey("Accept"))
            assertFalse(request.headers.containsKey("Origin"))
            assertFalse(request.headers.containsKey("Referer"))
            when (++step) {
                1 -> {
                    assertEquals("GET", request.method)
                    assertEquals("/cas/login", request.uri.path)
                    assertFalse(request.headers.containsKey("Cookie"))
                    SchoolResponse(200, mapOf("Set-Cookie" to listOf("CASID=first; Path=/cas/login; Secure", "route=first; Path=/; Secure")), "<input name=\"execution\" value=\"form-token\">".toByteArray())
                }
                2 -> {
                    assertEquals("GET", request.method)
                    assertEquals("/cas/v2/getPubKey", request.uri.path)
                    assertEquals(listOf("CASID=first; route=first"), request.headers["Cookie"])
                    SchoolResponse(200, mapOf("Set-Cookie" to listOf("route=second; Path=/; Secure")), """{"modulus":"ca1","exponent":"11"}""".toByteArray())
                }
                3 -> {
                    assertEquals("POST", request.method)
                    assertEquals("https://zjuam.zju.edu.cn/cas/login", request.uri.toString())
                    assertEquals(listOf("CASID=first; route=first; route=second"), request.headers["Cookie"])
                    assertEquals(listOf("application/x-www-form-urlencoded; charset=utf-8"), request.headers["Content-Type"])
                    assertEquals("username=fixture&password=${"ae6".padStart(128, '0')}&execution=form-token&_eventId=submit&rememberMe=true", request.body!!.toString(Charsets.UTF_8))
                    SchoolResponse(302, mapOf("Set-Cookie" to listOf("iPlanetDirectoryPro=fixture; Secure; Path=/"), "Location" to listOf("https://must-not-follow.zju.edu.cn/")), byteArrayOf())
                }
                else -> error("CAS verification must stop after the original POST response")
            }
        })
        client.verifyCredentials()
        assertEquals(3, step)
    }
    @Test fun casSessionCookiesUseOneBrowserStyleHeader() {
        val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture", "secret") }, SchoolTransport { request ->
            when {
                request.method == "POST" || request.uri.path.endsWith("getPubKey") -> {
                    val cookies = request.headers.getValue("Cookie")
                    assertEquals(1, cookies.size)
                    assertTrue(cookies.single().contains("JSESSIONID=fixture"))
                    assertTrue(cookies.single().contains("route=fixture-route"))
                    assertFalse(cookies.single().contains('$'))
                    assertFalse(cookies.single().contains('"'))
                    if (request.method == "POST") SchoolResponse(302, mapOf("Set-Cookie" to listOf("iPlanetDirectoryPro=fixture; Path=/; Secure")), byteArrayOf())
                    else SchoolResponse(200, emptyMap(), """{"modulus":"ca1","exponent":"11"}""".toByteArray())
                }
                else -> SchoolResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=fixture; Max-Age=600; Path=/cas; Secure; HttpOnly", "route=fixture-route; Path=/; Secure")), "<input name=\"execution\" value=\"token\">".toByteArray())
            }
        })
        client.verifyCredentials()
    }
    @Test fun onlySubmissionCookieConfirmsLoginAndMissingCookieIsNotCalledWrongPassword() {
        val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture", "secret") }, SchoolTransport { request ->
            when {
                request.method == "POST" -> SchoolResponse(200, emptyMap(), "<html>Unexpected authentication response</html>".toByteArray())
                request.uri.path.endsWith("getPubKey") -> SchoolResponse(200, emptyMap(), """{"modulus":"ca1","exponent":"11"}""".toByteArray())
                else -> SchoolResponse(200, mapOf("Set-Cookie" to listOf("iPlanetDirectoryPro=pre-auth; Path=/; Secure")), "<input name=\"execution\" value=\"token\">".toByteArray())
            }
        })
        val failure = assertThrows(SchoolException::class.java) { client.verifyCredentials() }
        assertTrue(failure.message!!.contains("HTTP 200"))
        assertTrue(failure.message!!.contains("没有返回 SSO"))
        assertFalse(failure.message!!.contains("密码不正确"))
        assertFalse(failure.message!!.contains("pre-auth"))
    }
    @Test fun explicitCheckMakesOneSubmissionAndClassifiesFailures() {
        for ((serverMessage, expected) in listOf("用户名或密码错误" to "学号或密码", "&#23494;&#30721;&#38169;&#35823;" to "学号或密码", "密码错误次数过多，账号锁定" to "已锁定", "请输入验证码" to "验证码", "Authentication failed for fixture with secret" to "[已隐藏]")) {
            var posts = 0
            val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture", "secret") }, SchoolTransport { request ->
                val content = when {
                    request.method == "POST" -> { posts++; "<p id=\"errormsg\">$serverMessage</p>" }
                    request.uri.path.endsWith("getPubKey") -> """{"modulus":"ca1","exponent":"11"}"""
                    else -> "<input name=\"execution\" value=\"token\">"
                }
                SchoolResponse(200, emptyMap(), content.toByteArray())
            })
            val error = assertThrows(SchoolException::class.java) { client.verifyCredentials() }
            assertTrue(error.message!!.contains(expected))
            assertFalse(error.message!!.contains("secret"))
            assertEquals(1, posts)
        }
    }

    @Test fun verifiedCredentialsDoNotFetchServiceData() {
        val requests = mutableListOf<SchoolRequest>()
        val client = ZjuClient(CredentialsProvider { SchoolCredentials("fixture", "secret") }, SchoolTransport { request ->
            requests += request
            when {
                request.method == "POST" -> SchoolResponse(302, mapOf("Set-Cookie" to listOf("iPlanetDirectoryPro=fixture; Path=/; Secure"), "Location" to listOf("https://zjuam.zju.edu.cn/")), byteArrayOf())
                request.uri.path.endsWith("getPubKey") -> SchoolResponse(200, emptyMap(), """{"modulus":"ca1","exponent":"11"}""".toByteArray())
                else -> SchoolResponse(200, emptyMap(), "<input name=\"execution\" value=\"token\">".toByteArray())
            }
        })
        client.verifyCredentials()
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.uri.host == "zjuam.zju.edu.cn" })
    }
}
