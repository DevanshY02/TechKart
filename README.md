# TechKart

TechKart is a simple dynamic PC parts store built with a Java backend and a browser-based frontend. It started as a console shopping app and now runs as a small full-stack web app with product browsing, cart checkout, and admin inventory tools.

## Features

- Product catalog with search and category filters
- Cart with quantity controls and checkout
- Order history saved to a text file
- Admin product add, edit, delete, and stock updates
- Low-stock report
- Lightweight file-based persistence using `products.txt` and `orders.txt`
- No Maven, npm, or database setup required

## Tech Stack

- Backend: Java built-in `HttpServer`
- Frontend: HTML, CSS, and JavaScript
- Storage: Plain text files

## Project Structure

```text
TechKart/
├── Main.java
├── products.txt
├── orders.txt
├── public/
│   ├── index.html
│   ├── styles.css
│   └── app.js
└── README.md
```

## How To Run

Compile the Java backend:

```bash
javac Main.java
```

Start the server:

```bash
java Main 8080
```

Open the app in your browser:

```text
http://localhost:8080
```

## Deploy Online

This app needs a web service host because it has a Java backend. GitHub Pages will not run the backend.

One simple option is Render:

1. Open [Render](https://render.com) and sign in.
2. Click **New** > **Web Service**.
3. Connect this GitHub repo: `https://github.com/DevanshY02/TechKart`
4. Choose the `main` branch.
5. Select Docker as the runtime. Render will use the included `Dockerfile`.
6. Set the health check path to:

```text
/api/health
```

7. Create the service and wait for the deploy to finish.

Render will give you a public URL like:

```text
https://techkart.onrender.com
```

The app reads the `PORT` environment variable automatically, so it can run on hosted platforms instead of only `localhost`.

## Admin Access

Use this demo PIN in the Admin tab:

```text
1234
```

## API Endpoints

- `GET /api/products`
- `GET /api/categories`
- `GET /api/summary`
- `POST /api/orders`
- `GET /api/orders`
- `POST /api/products`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}`
- `GET /api/reports/low-stock`

Admin-only endpoints require the `X-Admin-Pin` header or a `pin` value.

## Notes

This is a learning/demo project. The admin PIN is intentionally simple and stored in code, so replace it with real authentication before using this in production.

Product and order changes are saved to local text files. On many free hosting services, those runtime file changes can reset after a redeploy or restart. Use a database or persistent disk if you need permanent hosted data.
