/**
 * A person who can use the chat: either a student or a case manager.
 *
 * Every student has exactly one case manager, and each student gets one
 * private conversation ("thread") with that case manager. We identify a
 * thread by the student's id, since there is one thread per student.
 */
record User(String id, String name, Role role, String caseManagerId) {

    enum Role { STUDENT, CASE_MANAGER }

    boolean isStudent() {
        return role == Role.STUDENT;
    }

    String toJson() {
        return "{"
            + "\"id\":" + Json.str(id) + ","
            + "\"name\":" + Json.str(name) + ","
            + "\"role\":" + Json.str(isStudent() ? "student" : "case_manager") + ","
            + "\"caseManagerId\":" + Json.str(caseManagerId)
            + "}";
    }
}
