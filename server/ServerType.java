/**
 * This is an enum class that represents the type of server.
 * 
 * @author Zijie Huang
 */
public enum ServerType {
    Coordinator,
    FRONT,
    MIDDLE;

    @Override
    public String toString() {
        switch (this) {
            case Coordinator:
                return "Coordinator";
            case FRONT:
                return "Front";
            case MIDDLE:
                return "Middle";
            default:
                return "Unknown";
        }
    }
}
