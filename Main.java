import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;

class Product {
    private final int id;
    private String name;
    private String category;
    private double price;
    private int stock;

    public Product(int id, String name, String category, double price, int stock) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
    }

    public String toFileString() {
        return id + "|" + clean(name) + "|" + clean(category) + "|" + price + "|" + stock;
    }

    public static Product fromFileString(String line) {
        String[] parts = line.split("\\|", -1);

        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid product row: " + line);
        }

        int id = Integer.parseInt(parts[0]);
        String name = parts[1];
        String category = parts[2];
        double price = Double.parseDouble(parts[3]);
        int stock = Integer.parseInt(parts[4]);

        return new Product(id, name, category, price, stock);
    }

    public String toJson() {
        return String.format(
                Locale.US,
                "{\"id\":%d,\"name\":\"%s\",\"category\":\"%s\",\"price\":%.2f,\"stock\":%d}",
                id,
                Main.escapeJson(name),
                Main.escapeJson(category),
                price,
                stock
        );
    }

    private static String clean(String value) {
        return value.replace("|", " ").trim();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public double getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public void reduceStock(int quantity) {
        stock -= quantity;
    }
}

class Order {
    private final int orderId;
    private final String dateTime;
    private final double totalAmount;
    private final String status;
    private final String details;

    public Order(int orderId, String dateTime, double totalAmount, String status, String details) {
        this.orderId = orderId;
        this.dateTime = dateTime;
        this.totalAmount = totalAmount;
        this.status = status;
        this.details = details;
    }

    public String toFileString() {
        return orderId + "|" + dateTime + "|" + totalAmount + "|" + status + "|" + details.replace("|", " ");
    }

    public static Order fromFileString(String line) {
        String[] parts = line.split("\\|", 5);

        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid order row: " + line);
        }

        int orderId = Integer.parseInt(parts[0]);
        String dateTime = parts[1];
        double totalAmount = Double.parseDouble(parts[2]);
        String status = parts[3];
        String details = parts[4];

        return new Order(orderId, dateTime, totalAmount, status, details);
    }

    public String toJson() {
        return String.format(
                Locale.US,
                "{\"orderId\":%d,\"dateTime\":\"%s\",\"totalAmount\":%.2f,\"status\":\"%s\",\"details\":\"%s\"}",
                orderId,
                Main.escapeJson(dateTime),
                totalAmount,
                Main.escapeJson(status),
                Main.escapeJson(details)
        );
    }

    public int getOrderId() {
        return orderId;
    }

    public double getTotalAmount() {
        return totalAmount;
    }
}

class CartRequestItem {
    private final int productId;
    private final int quantity;

    CartRequestItem(int productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public int getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}

class ApiException extends RuntimeException {
    private final int statusCode;

    ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}

public class Main {
    private static final String ADMIN_PIN = "1234";
    private static final Path PRODUCTS_FILE = Paths.get("products.txt");
    private static final Path ORDERS_FILE = Paths.get("orders.txt");
    private static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath().normalize();
    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object STORE_LOCK = new Object();

    private static final ArrayList<Product> products = new ArrayList<>();
    private static final ArrayList<Order> orders = new ArrayList<>();
    private static int nextProductId = 1;
    private static int nextOrderId = 1001;

    public static void main(String[] args) throws IOException {
        loadProductsFromFile();
        loadOrdersFromFile();

        int port = getPort(args);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", Main::handleRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("TechKart is running at http://localhost:" + port);
        System.out.println("Admin PIN: " + ADMIN_PIN);
    }

    private static int getPort(String[] args) {
        if (args.length > 0) {
            return Integer.parseInt(args[0]);
        }

        String envPort = System.getenv("PORT");

        if (envPort != null && !envPort.isBlank()) {
            return Integer.parseInt(envPort);
        }

        return 8080;
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        addCommonHeaders(exchange);

        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                serveStaticFile(exchange, path);
            }
        } catch (ApiException e) {
            sendJson(exchange, e.getStatusCode(), "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, "{\"error\":\"Server error: " + escapeJson(e.getMessage()) + "\"}");
        } finally {
            exchange.close();
        }
    }

    private static void addCommonHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type,X-Admin-Pin");
        headers.set("Cache-Control", "no-store");
    }

    private static void handleApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (path.equals("/api/health") && method.equals("GET")) {
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
            return;
        }

        if (path.equals("/api/summary") && method.equals("GET")) {
            sendJson(exchange, 200, buildSummaryJson());
            return;
        }

        if (path.equals("/api/products")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, buildProductsJson(exchange));
                return;
            }

            if (method.equals("POST")) {
                sendJson(exchange, 201, createProduct(exchange));
                return;
            }
        }

        if (path.startsWith("/api/products/")) {
            int productId = parsePathId(path, "/api/products/");

            if (method.equals("PUT") || method.equals("PATCH")) {
                sendJson(exchange, 200, updateProduct(exchange, productId));
                return;
            }

            if (method.equals("DELETE")) {
                sendJson(exchange, 200, deleteProduct(exchange, productId));
                return;
            }
        }

        if (path.equals("/api/categories") && method.equals("GET")) {
            sendJson(exchange, 200, buildCategoriesJson());
            return;
        }

        if (path.equals("/api/orders")) {
            if (method.equals("GET")) {
                requireAdmin(exchange, Map.of());
                sendJson(exchange, 200, buildOrdersJson());
                return;
            }

            if (method.equals("POST")) {
                sendJson(exchange, 201, checkout(exchange));
                return;
            }
        }

        if (path.equals("/api/reports/low-stock") && method.equals("GET")) {
            requireAdmin(exchange, Map.of());
            sendJson(exchange, 200, buildLowStockJson(exchange));
            return;
        }

        throw new ApiException(404, "Endpoint not found.");
    }

    private static int parsePathId(String path, String prefix) {
        String rest = path.substring(prefix.length());

        if (rest.contains("/") || rest.isBlank()) {
            throw new ApiException(404, "Endpoint not found.");
        }

        try {
            return Integer.parseInt(rest);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Invalid product id.");
        }
    }

    private static String buildProductsJson(HttpExchange exchange) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String search = query.getOrDefault("search", "").trim().toLowerCase(Locale.ROOT);
        String category = query.getOrDefault("category", "").trim().toLowerCase(Locale.ROOT);
        StringBuilder json = new StringBuilder("{\"products\":[");
        int count = 0;

        synchronized (STORE_LOCK) {
            for (Product product : products) {
                boolean matchesSearch = search.isEmpty()
                        || product.getName().toLowerCase(Locale.ROOT).contains(search)
                        || product.getCategory().toLowerCase(Locale.ROOT).contains(search);
                boolean matchesCategory = category.isEmpty()
                        || category.equals("all")
                        || product.getCategory().equalsIgnoreCase(category);

                if (!matchesSearch || !matchesCategory) {
                    continue;
                }

                if (count > 0) {
                    json.append(",");
                }

                json.append(product.toJson());
                count++;
            }
        }

        json.append("],\"total\":").append(count).append("}");
        return json.toString();
    }

    private static String buildCategoriesJson() {
        TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        synchronized (STORE_LOCK) {
            for (Product product : products) {
                categories.add(product.getCategory());
            }
        }

        StringBuilder json = new StringBuilder("{\"categories\":[");
        int index = 0;

        for (String category : categories) {
            if (index > 0) {
                json.append(",");
            }

            json.append("\"").append(escapeJson(category)).append("\"");
            index++;
        }

        json.append("]}");
        return json.toString();
    }

    private static String buildOrdersJson() {
        StringBuilder json = new StringBuilder("{\"orders\":[");

        synchronized (STORE_LOCK) {
            for (int i = orders.size() - 1; i >= 0; i--) {
                if (i < orders.size() - 1) {
                    json.append(",");
                }

                json.append(orders.get(i).toJson());
            }
        }

        json.append("]}");
        return json.toString();
    }

    private static String buildLowStockJson(HttpExchange exchange) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int limit = parseInt(query.getOrDefault("limit", "5"), "limit");
        StringBuilder json = new StringBuilder("{\"products\":[");
        int count = 0;

        synchronized (STORE_LOCK) {
            for (Product product : products) {
                if (product.getStock() > limit) {
                    continue;
                }

                if (count > 0) {
                    json.append(",");
                }

                json.append(product.toJson());
                count++;
            }
        }

        json.append("],\"limit\":").append(limit).append("}");
        return json.toString();
    }

    private static String buildSummaryJson() {
        int productCount;
        int totalStock = 0;
        int lowStockCount = 0;
        double inventoryValue = 0;
        TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        synchronized (STORE_LOCK) {
            productCount = products.size();

            for (Product product : products) {
                totalStock += product.getStock();
                inventoryValue += product.getPrice() * product.getStock();
                categories.add(product.getCategory());

                if (product.getStock() <= 5) {
                    lowStockCount++;
                }
            }

            return String.format(
                    Locale.US,
                    "{\"productCount\":%d,\"categoryCount\":%d,\"totalStock\":%d,\"lowStockCount\":%d,\"orderCount\":%d,\"inventoryValue\":%.2f}",
                    productCount,
                    categories.size(),
                    totalStock,
                    lowStockCount,
                    orders.size(),
                    inventoryValue
            );
        }
    }

    private static String createProduct(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        requireAdmin(exchange, form);

        String name = requireText(form, "name");
        String category = requireText(form, "category");
        double price = parseDouble(form.get("price"), "price");
        int stock = parseInt(form.get("stock"), "stock");

        if (price <= 0) {
            throw new ApiException(400, "Price must be greater than 0.");
        }

        if (stock < 0) {
            throw new ApiException(400, "Stock cannot be negative.");
        }

        Product product;

        synchronized (STORE_LOCK) {
            product = new Product(nextProductId, name, category, price, stock);
            products.add(product);
            nextProductId++;
            saveProductsToFile();
        }

        return "{\"message\":\"Product added.\",\"product\":" + product.toJson() + "}";
    }

    private static String updateProduct(HttpExchange exchange, int productId) throws IOException {
        Map<String, String> form = readForm(exchange);
        requireAdmin(exchange, form);

        synchronized (STORE_LOCK) {
            Product product = findProductById(productId);

            if (product == null) {
                throw new ApiException(404, "Product not found.");
            }

            if (hasText(form.get("name"))) {
                product.setName(form.get("name").trim());
            }

            if (hasText(form.get("category"))) {
                product.setCategory(form.get("category").trim());
            }

            if (hasText(form.get("price"))) {
                double price = parseDouble(form.get("price"), "price");

                if (price <= 0) {
                    throw new ApiException(400, "Price must be greater than 0.");
                }

                product.setPrice(price);
            }

            if (hasText(form.get("stock"))) {
                int stock = parseInt(form.get("stock"), "stock");

                if (stock < 0) {
                    throw new ApiException(400, "Stock cannot be negative.");
                }

                product.setStock(stock);
            }

            saveProductsToFile();
            return "{\"message\":\"Product updated.\",\"product\":" + product.toJson() + "}";
        }
    }

    private static String deleteProduct(HttpExchange exchange, int productId) throws IOException {
        Map<String, String> form = readForm(exchange);
        requireAdmin(exchange, form);

        synchronized (STORE_LOCK) {
            Product product = findProductById(productId);

            if (product == null) {
                throw new ApiException(404, "Product not found.");
            }

            products.remove(product);
            saveProductsToFile();
            return "{\"message\":\"Product removed.\",\"product\":" + product.toJson() + "}";
        }
    }

    private static String checkout(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        List<CartRequestItem> requestedItems = parseCartItems(form.get("items"));

        synchronized (STORE_LOCK) {
            if (requestedItems.isEmpty()) {
                throw new ApiException(400, "Cart is empty.");
            }

            double total = 0;
            StringBuilder details = new StringBuilder();

            for (CartRequestItem item : requestedItems) {
                Product product = findProductById(item.getProductId());

                if (product == null) {
                    throw new ApiException(404, "Product " + item.getProductId() + " is no longer available.");
                }

                if (item.getQuantity() <= 0) {
                    throw new ApiException(400, "Quantity must be greater than 0.");
                }

                if (item.getQuantity() > product.getStock()) {
                    throw new ApiException(409, "Only " + product.getStock() + " units available for " + product.getName() + ".");
                }

                double lineTotal = product.getPrice() * item.getQuantity();
                total += lineTotal;
                details.append(product.getName())
                        .append(" x ")
                        .append(item.getQuantity())
                        .append(" = Rs.")
                        .append(String.format(Locale.US, "%.2f", lineTotal))
                        .append("; ");
            }

            for (CartRequestItem item : requestedItems) {
                Product product = findProductById(item.getProductId());
                product.reduceStock(item.getQuantity());
            }

            String dateTime = LocalDateTime.now().format(ORDER_DATE_FORMAT);
            Order order = new Order(nextOrderId, dateTime, total, "PLACED", details.toString());
            orders.add(order);
            nextOrderId++;

            saveProductsToFile();
            saveOrdersToFile();

            return "{\"message\":\"Order placed.\",\"order\":" + order.toJson() + "}";
        }
    }

    private static List<CartRequestItem> parseCartItems(String rawItems) {
        ArrayList<CartRequestItem> items = new ArrayList<>();

        if (!hasText(rawItems)) {
            return items;
        }

        String[] pairs = rawItems.split(",");

        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            String[] parts = pair.split(":", 2);

            if (parts.length != 2) {
                throw new ApiException(400, "Invalid cart item format.");
            }

            int productId = parseInt(parts[0], "product id");
            int quantity = parseInt(parts[1], "quantity");
            items.add(new CartRequestItem(productId, quantity));
        }

        return items;
    }

    private static Product findProductById(int id) {
        for (Product product : products) {
            if (product.getId() == id) {
                return product;
            }
        }

        return null;
    }

    private static void loadProductsFromFile() {
        synchronized (STORE_LOCK) {
            products.clear();

            if (!Files.exists(PRODUCTS_FILE)) {
                addDefaultProducts();
                saveProductsToFile();
                return;
            }

            int maxId = 0;

            try (BufferedReader reader = Files.newBufferedReader(PRODUCTS_FILE, StandardCharsets.UTF_8)) {
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }

                    Product product = Product.fromFileString(line);
                    products.add(product);
                    maxId = Math.max(maxId, product.getId());
                }

                nextProductId = maxId + 1;
            } catch (Exception e) {
                System.out.println("Error loading products: " + e.getMessage());
                products.clear();
                addDefaultProducts();
            }
        }
    }

    private static void saveProductsToFile() {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(PRODUCTS_FILE, StandardCharsets.UTF_8))) {
            for (Product product : products) {
                writer.println(product.toFileString());
            }
        } catch (Exception e) {
            throw new ApiException(500, "Could not save products: " + e.getMessage());
        }
    }

    private static void loadOrdersFromFile() {
        synchronized (STORE_LOCK) {
            orders.clear();

            if (!Files.exists(ORDERS_FILE)) {
                return;
            }

            int maxOrderId = 1000;

            try (BufferedReader reader = Files.newBufferedReader(ORDERS_FILE, StandardCharsets.UTF_8)) {
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }

                    Order order = Order.fromFileString(line);
                    orders.add(order);
                    maxOrderId = Math.max(maxOrderId, order.getOrderId());
                }

                nextOrderId = maxOrderId + 1;
            } catch (Exception e) {
                System.out.println("Error loading orders: " + e.getMessage());
            }
        }
    }

    private static void saveOrdersToFile() {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(ORDERS_FILE, StandardCharsets.UTF_8))) {
            for (Order order : orders) {
                writer.println(order.toFileString());
            }
        } catch (Exception e) {
            throw new ApiException(500, "Could not save orders: " + e.getMessage());
        }
    }

    private static void addDefaultProducts() {
        products.add(new Product(1, "Ryzen 5 5600", "CPU", 12000, 10));
        products.add(new Product(2, "RTX 4060", "GPU", 28000, 5));
        products.add(new Product(3, "16GB DDR4 RAM", "RAM", 3200, 20));
        products.add(new Product(4, "1TB NVMe SSD", "Storage", 5000, 15));
        products.add(new Product(5, "MSI B550 Motherboard", "Motherboard", 9500, 8));
        products.add(new Product(6, "650W Power Supply", "PSU", 4500, 12));
        nextProductId = 7;
    }

    private static void requireAdmin(HttpExchange exchange, Map<String, String> form) {
        String pin = exchange.getRequestHeaders().getFirst("X-Admin-Pin");

        if (!hasText(pin)) {
            pin = form.get("pin");
        }

        if (!hasText(pin)) {
            pin = parseQuery(exchange.getRequestURI().getRawQuery()).get("pin");
        }

        if (!ADMIN_PIN.equals(pin)) {
            throw new ApiException(401, "Invalid admin PIN.");
        }
    }

    private static String requireText(Map<String, String> form, String fieldName) {
        String value = form.get(fieldName);

        if (!hasText(value)) {
            throw new ApiException(400, fieldName + " is required.");
        }

        return value.trim();
    }

    private static int parseInt(String value, String fieldName) {
        if (!hasText(value)) {
            throw new ApiException(400, fieldName + " is required.");
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, fieldName + " must be a valid number.");
        }
    }

    private static double parseDouble(String value, String fieldName) {
        if (!hasText(value)) {
            throw new ApiException(400, fieldName + " is required.");
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, fieldName + " must be a valid amount.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseQuery(body);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();

        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }

        String[] pairs = rawQuery.split("&");

        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            int separator = pair.indexOf("=");
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            values.put(decodeUrl(key), decodeUrl(value));
        }

        return values;
    }

    private static String decodeUrl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void serveStaticFile(HttpExchange exchange, String rawPath) throws IOException {
        String cleanPath = rawPath.equals("/") ? "/index.html" : rawPath;
        Path file = PUBLIC_DIR.resolve(cleanPath.substring(1)).normalize();

        if (!file.startsWith(PUBLIC_DIR) || Files.isDirectory(file) || !Files.exists(file)) {
            sendText(exchange, 404, "Not found");
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }

        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }

        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }

        if (name.endsWith(".png")) {
            return "image/png";
        }

        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }

        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);

            switch (current) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (current < 32) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                    break;
            }
        }

        return escaped.toString();
    }
}
