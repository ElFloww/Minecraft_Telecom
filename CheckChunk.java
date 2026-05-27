import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public class CheckChunk {
    public static void main(String[] args) {
        // Just defining this to see compile errors
    }
    public void test(ChunkAccess chunk) {
        ChunkStatus status = chunk.getStatus();
    }
}
