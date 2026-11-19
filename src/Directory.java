import java.util.ArrayList;
import java.util.List;

/**
 * The list of people in the demo, and the rules for who may see what.
 *
 * DEMO ONLY: these are made-up names. A real version would get people and
 * case-manager assignments from the school's systems -- never type real
 * student information into a demo.
 */
final class Directory {

    private Directory() { }

    static final List<User> ALL = List.of(
        new User("cm1", "Ms. Rivera", User.Role.CASE_MANAGER, null),
        new User("cm2", "Mr. Okafor", User.Role.CASE_MANAGER, null),
        new User("s1", "Alex P.",   User.Role.STUDENT, "cm1"),
        new User("s2", "Jordan K.", User.Role.STUDENT, "cm1"),
        new User("s3", "Sam T.",    User.Role.STUDENT, "cm2"),
        new User("s4", "Taylor M.", User.Role.STUDENT, "cm2")
    );

    /** Looks up a user by id. Returns null if there is no such user. */
    static User find(String id) {
        for (User u : ALL) {
            if (u.id().equals(id)) {
                return u;
            }
        }
        return null;
    }

    /** The threads a user can see: a student sees their own; a case manager sees their students'. */
    static List<User> threadsFor(User user) {
        List<User> students = new ArrayList<>();
        for (User u : ALL) {
            if (u.isStudent() && (u.id().equals(user.id()) || user.id().equals(u.caseManagerId()))) {
                students.add(u);
            }
        }
        return students;
    }

    /**
     * THE most important rule in the app: may this user read or post in this student's thread?
     * Only the student themselves and their assigned case manager may.
     * Every request that touches messages checks this on the SERVER -- never trust the browser.
     */
    static boolean canAccessThread(User user, String studentId) {
        User student = find(studentId);
        if (user == null || student == null || !student.isStudent()) {
            return false;
        }
        return user.id().equals(student.id()) || user.id().equals(student.caseManagerId());
    }

    /** Everyone who should receive live updates for a thread. */
    static List<String> participants(String studentId) {
        User student = find(studentId);
        return List.of(student.id(), student.caseManagerId());
    }
}
