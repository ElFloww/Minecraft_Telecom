public class TestColor {
    public static void main(String[] args) {
        int col = 8368696; // typical grass
        int color = 0xFF000000 | col;
        System.out.println(Integer.toHexString(color));
    }
}
