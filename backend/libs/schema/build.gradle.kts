// The Flyway migrations (classpath db/migration). Applied files are byte-frozen: Flyway checks
// their checksums.
plugins { `java-library` }

group = rootProject.group

version = rootProject.version

base.archivesName = "helper2-schema"
