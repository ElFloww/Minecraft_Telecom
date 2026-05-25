import struct
import zlib

def write_png(filename, width, height, pixels):
    def png_chunk(chunk_type, data):
        return struct.pack('>I', len(data)) + chunk_type + data + struct.pack('>I', zlib.crc32(chunk_type + data) & 0xffffffff)

    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    ihdr = png_chunk(b'IHDR', ihdr_data)
    
    raw_data = b''
    for y in range(height):
        raw_data += b'\x00'
        for x in range(width):
            raw_data += struct.pack('4B', *pixels[y*width + x])
    
    idat = png_chunk(b'IDAT', zlib.compress(raw_data))
    iend = png_chunk(b'IEND', b'')
    
    with open(filename, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + ihdr + idat + iend)

pixels = []
for y in range(16):
    for x in range(16):
        if x >= 2 and x <= 13 and y >= 2 and y <= 13:
            if x == 2 or x == 13 or y == 2 or y == 13:
                pixels.append((200, 200, 0, 255)) # Yellow rugged case
            elif y == 3 and x > 2 and x < 13:
                pixels.append((0, 85, 170, 255)) # Blue header
            else:
                pixels.append((30, 30, 30, 255)) # Dark screen
        elif x == 14 and y == 11:
            pixels.append((255, 0, 0, 255)) # Red button
        else:
            pixels.append((0, 0, 0, 0)) # Transparent
write_png("src/main/resources/assets/telecom/textures/item/network_tool.png", 16, 16, pixels)
print("Generated network_tool texture.")
