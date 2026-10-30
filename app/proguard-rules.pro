# R8 keep rules for release builds. Add a rule here only when a release build shows a class that
# reflection or a service loader needs and that R8 removed.

# Ktor's IntelliJ debugger check refers to java.lang.management, which Android does not ship. The check runs only under
# an IDE debugger, so the missing classes are never loaded on a phone. R8 otherwise stops with "Missing classes".
-dontwarn java.lang.management.**
