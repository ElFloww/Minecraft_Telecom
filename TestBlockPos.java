public class TestBlockPos {
    public static void main(String[] args) {
        long l = 150358213439557L;
        int x = (int)(l >> 38);
        int y = (int)((l << 52) >> 52);
        int z = (int)((l << 26) >> 38);
        System.out.println("x: " + x + " y: " + y + " z: " + z);
    }
}
