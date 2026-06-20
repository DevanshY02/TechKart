FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY Main.java products.txt orders.txt ./
COPY public ./public

RUN javac Main.java

EXPOSE 10000

CMD ["sh", "-c", "java Main ${PORT:-10000}"]
