# Immutable pre-scalarization Hibernate export

`hibernate-baseline.sql` was exported offline from the compiled entity metadata of local source commit `64a25660be211fe79275e4c628cecd3a1c0e0a9e`, whose source tree is identical to published commit `8e185d5f8f1ed6ea4f97df67a26f43d815850937` (tree `f67b6df4ad4df678186a0c435293b0b7bd753f98`) using `com.moviemux.migration.SchemaMetadata`, Hibernate 6.6.18.Final and PostgreSQL dialect 14.22. Entity source files in the export checkout were compared byte-for-byte against that Git commit. No database or application startup was used.

Source: https://github.com/HenriqueMendesCoelho/moviemux-api/commit/8e185d5f8f1ed6ea4f97df67a26f43d815850937

SHA-256 (unmodified export bytes): `062eda9e4b8b539a99c2ed87f40adda9da645f08e46828d5b6b98797036aa4d5`.

V1 retains all 12 foreign keys. Current JPA metadata deliberately omits only the three intermodule user foreign keys listed exactly in SchemaExportTest; Flyway owns those constraints. This fixture is historical evidence, not a template to regenerate when mappings change. The PostgreSQL test compares complete materialized catalogs against this independent baseline.

Scalar embedded-key mapping also places `movie_note.user_id` first in current Hibernate CREATE TABLE output. The metadata test asserts that one exact permutation explicitly; definitions and primary-key order are unchanged. V1 and the historical catalog retain the original physical column order. No broad column-order normalization is performed.
