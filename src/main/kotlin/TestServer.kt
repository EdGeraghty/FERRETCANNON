import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun main() {
    println("Starting simple test server...")
    embeddedServer(Netty, port = 8081, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText("Hello from simple test server!", ContentType.Text.Plain)
            }
        }
    }.start(wait = true)
    println("Simple test server started!")
}
