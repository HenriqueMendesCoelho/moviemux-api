# MovieMux - API

## Introduction

This is a personal project aimed at creating a comprehensive movie rating portal. Whether you're a movie buff or simply
looking for a way to discover new films.

Inspired by IMDB and TMDB.

## Technologies

```
Java 20, Spring Boot, JPA, Spring Security, Lombok, Spring Validation, Actuator
```

### Link

[MovieMux Frontend](https://github.com/HenriqueMendesCoelho/moviemux)

This product uses the TMDB API but is not endorsed or certified by TMDB.

## 🚀 Rodando o projeto no Windows

Se você estiver utilizando **Windows**, use o comando abaixo para compilar o projeto:

```bash
.\mvnw.cmd clean compile
````

### ⚠️ Atenção

No Windows **não utilize**:

```bash
./mvnw clean compile
```

Esse comando é para Linux/macOS e pode gerar erro ou `Exit code 1`.

### 💡 Dica

O projeto possui dois arquivos de wrapper do Maven:

* `mvnw` → Linux / macOS
* `mvnw.cmd` → Windows

Use o script correspondente ao seu sistema operacional.

---

## ▶️ Executar a aplicação

Para rodar a aplicação no Windows:

```bash
.\mvnw.cmd spring-boot:run
```

Ou para gerar o pacote:

```bash
.\mvnw.cmd clean package
```
