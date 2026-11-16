# Synthetic develop schema

Source: https://github.com/HenriqueMendesCoelho/moviemux-api/commit/2b04279c4cb232d17e828e181fa51c26f0036550

`develop-2b04279-schema.sql` reconstructs the PostgreSQL schema created by that public source's Hibernate `ddl-auto=update` configuration. It is not a deployment dump and contains no rows or production configuration.

The table and foreign-key definitions were exported offline from the compiled entities at that revision, using Hibernate 6.6.33.Final and PostgreSQL dialect 14.22. The eight exact unique-key names were read from those same Hibernate metadata objects after export. The ordinary create export emits unnamed UNIQUE declarations; Hibernate update instead materializes the named metadata constraints. This fixture replaces only those eight declarations with their proven names. PostgreSQL folds the unquoted `UK...` names to lowercase.

SHA-256: `f885293b179ecc238c0bd309cd5dba732f16492546813afc3bdfcd1de2033196`.

`SchemaExportTest` pins these bytes and explicitly compares all definitions with the original immutable baseline after reversing only the eight named-constraint substitutions. `FlywayMigrationIT` materializes this fixture in an isolated synthetic PostgreSQL schema, rejects automatic adoption, explicitly baselines the inspected fixture, preserves the complete catalog and existing invite identity, validates current JPA, and exercises the real wishlist adapter's duplicate and foreign-key behavior.

The historical `hibernate-baseline.sql` fixture and V1 remain unchanged. These tests do not authorize or establish the safety of adopting any existing deployment database.
