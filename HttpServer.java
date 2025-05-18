import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class HttpServer {
    private static final int PORT = 8080;
    private static final String WWW_ROOT = "./www";
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
        Map.entry(".html", "text/html"),
        Map.entry(".htm", "text/html"),
        Map.entry(".jpg", "image/jpeg"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".png", "image/png"),
        Map.entry(".gif", "image/gif"),
        Map.entry(".css", "text/css"),
        Map.entry(".js", "application/javascript"),
        Map.entry(".pdf", "application/pdf"),
        Map.entry(".zip", "application/zip"),
        Map.entry(".mp4", "video/mp4"),
        Map.entry(".webm", "video/webm"),
        Map.entry(".ogg", "audio/ogg"),
        Map.entry(".mp3", "audio/mpeg")
    );
    private static final Object fileLock = new Object();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening on port " + PORT + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream out = clientSocket.getOutputStream()) {

            HttpRequest request = parseRequest(in);
            String response = generateResponse(request);
            out.write(response.getBytes());
            out.flush();
        } catch (IOException e) {
            System.err.println("Client handling exception: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    private static HttpRequest parseRequest(BufferedReader in) throws IOException {
        HttpRequest request = new HttpRequest();
        String line = in.readLine();
        if (line == null) return request;

        // Parse request line
        String[] requestLine = line.split(" ");
        if (requestLine.length >= 3) {
            request.method = requestLine[0];
            request.resource = requestLine[1];
            request.version = requestLine[2];
        }

        // Parse headers
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colonPos = line.indexOf(':');
            if (colonPos > 0) {
                String key = line.substring(0, colonPos).trim();
                String value = line.substring(colonPos + 1).trim();
                request.headers.put(key, value);
            }
        }

        // Parse body if Content-Length exists
        if (request.headers.containsKey("Content-Length")) {
            int contentLength = Integer.parseInt(request.headers.get("Content-Length"));
            char[] body = new char[contentLength];
            in.read(body, 0, contentLength);
            request.body = new String(body);
        }

        return request;
    }

    private static String generateResponse(HttpRequest request) throws IOException {
        String resource = request.resource.equals("/") ? "/index.html" : request.resource;
        Path filePath = Paths.get(WWW_ROOT, resource);

        switch (request.method) {
            case "GET":
            case "HEAD":
                if (Files.isDirectory(filePath)) {
                    filePath = filePath.resolve("index.html");
                }
                
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    byte[] fileContent = Files.readAllBytes(filePath);
                    String mimeType = getMimeType(filePath.toString());
                    
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: " + mimeType + "\r\n" +
                            "Content-Length: " + fileContent.length + "\r\n" +
                            "Connection: close\r\n";
                    
                    if (request.method.equals("GET")) {
                        response += "\r\n";
                        return response + new String(fileContent);
                    } else {
                        return response + "\r\n";
                    }
                } else {
                    String body = "<h1>404 Not Found</h1>";
                    return "HTTP/1.1 404 Not Found\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: " + body.length() + "\r\n" +
                            "Connection: close\r\n\r\n" + body;
                }
                
            case "POST":
                synchronized (fileLock) {
                    Files.createDirectories(filePath.getParent());
                    Files.write(filePath, request.body.getBytes());
                    return "HTTP/1.1 201 Created\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n";
                }
                
            case "PUT":
                synchronized (fileLock) {
                    Files.createDirectories(filePath.getParent());
                    Files.write(filePath, request.body.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n";
                }
                
            case "DELETE":
                synchronized (fileLock) {
                    if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                        Files.delete(filePath);
                        return "HTTP/1.1 204 No Content\r\n" +
                                "Connection: close\r\n\r\n";
                    } else {
                        String body = "<h1>404 Not Found</h1>";
                        return "HTTP/1.1 404 Not Found\r\n" +
                                "Content-Type: text/html\r\n" +
                                "Content-Length: " + body.length() + "\r\n" +
                                "Connection: close\r\n\r\n" + body;
                    }
                }
                
            default:
                String body = "<h1>405 Method Not Allowed</h1>";
                return "HTTP/1.1 405 Method Not Allowed\r\n" +
                        "Content-Type: text/html\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n\r\n" + body;
        }
    }

    private static String getMimeType(String filePath) {
        int dotPos = filePath.lastIndexOf('.');
        if (dotPos > 0 && dotPos < filePath.length() - 1) {
            String extension = filePath.substring(dotPos);
            return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
        }
        return "application/octet-stream";
    }

    static class HttpRequest {
        String method = "";
        String resource = "";
        String version = "";
        Map<String, String> headers = new HashMap<>();
        String body = "";
    }
}