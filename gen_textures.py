import struct
import zlib
import os

def write_png(filename, width, height, pixels):
    # pixels is a list of (R,G,B,A) tuples
    def png_chunk(chunk_type, data):
        return struct.pack('>I', len(data)) + chunk_type + data + struct.pack('>I', zlib.crc32(chunk_type + data) & 0xffffffff)

    # IHDR
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    ihdr = png_chunk(b'IHDR', ihdr_data)
    
    # IDAT
    raw_data = b''
    for y in range(height):
        raw_data += b'\x00' # filter type 0
        for x in range(width):
            raw_data += struct.pack('4B', *pixels[y*width + x])
    
    idat = png_chunk(b'IDAT', zlib.compress(raw_data))
    
    # IEND
    iend = png_chunk(b'IEND', b'')
    
    with open(filename, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + ihdr + idat + iend)

# Colors
C_COPPER = (217, 107, 30, 255)
C_RUBBER = (30, 30, 30, 255)
C_FIBER = (135, 206, 235, 255) # Sky blue
C_CORE = (255, 255, 255, 255) # White core

# Copper Cable (16x16)
pixels = []
for y in range(16):
    for x in range(16):
        if x in (7, 8) or y in (7, 8):
            pixels.append(C_COPPER)
        else:
            pixels.append(C_RUBBER)
write_png("src/main/resources/assets/telecom/textures/block/copper_cable.png", 16, 16, pixels)

# Fiber Cable (16x16)
pixels = []
for y in range(16):
    for x in range(16):
        if x in (7, 8) and y in (7, 8):
            pixels.append(C_CORE)
        elif x in (6,7,8,9) and y in (6,7,8,9):
            pixels.append(C_FIBER)
        else:
            pixels.append((C_FIBER[0]-50, C_FIBER[1]-50, C_FIBER[2]-50, 255)) # Darker blue shell
write_png("src/main/resources/assets/telecom/textures/block/fiber_cable.png", 16, 16, pixels)

# Server (16x16) - Metal with LEDs
pixels = []
for y in range(16):
    for x in range(16):
        if x == 0 or x == 15 or y == 0 or y == 15:
            pixels.append((50, 50, 50, 255)) # Frame
        elif y in (2, 6, 10) and x > 2 and x < 13:
            if x % 2 == 0:
                pixels.append((0, 255, 0, 255)) # Green LED
            else:
                pixels.append((0, 200, 255, 255)) # Blue LED
        elif y in (3, 7, 11) and x > 2 and x < 13:
            pixels.append((20, 20, 20, 255)) # Rack slots
        else:
            pixels.append((30, 30, 30, 255)) # Base metal
write_png("src/main/resources/assets/telecom/textures/block/server.png", 16, 16, pixels)

# Router (16x16)
pixels = []
for y in range(16):
    for x in range(16):
        if y == 15:
            pixels.append((100, 100, 100, 255)) # Base
        elif y == 14 and x > 2 and x < 13:
            if x % 3 == 0:
                pixels.append((0, 255, 0, 255)) # LEDs
            else:
                pixels.append((200, 200, 200, 255)) # White plastic
        elif y > 8 and x > 1 and x < 14:
            pixels.append((220, 220, 220, 255)) # White plastic body
        elif (x == 3 or x == 12) and y <= 8:
            pixels.append((50, 50, 50, 255)) # Antennas
        else:
            pixels.append((0, 0, 0, 0)) # Transparent
write_png("src/main/resources/assets/telecom/textures/block/router.png", 16, 16, pixels)

# Antenna (16x16)
pixels = []
for y in range(16):
    for x in range(16):
        if x in (7, 8):
            pixels.append((150, 150, 150, 255)) # Pole
        elif y in (2, 4, 6) and x > 4 and x < 11:
            pixels.append((255, 255, 255, 255)) # Emitors
        else:
            pixels.append((0, 0, 0, 0)) # Transparent
write_png("src/main/resources/assets/telecom/textures/block/antenna.png", 16, 16, pixels)

# Smartphone (16x16)
pixels = []
for y in range(16):
    for x in range(16):
        if x >= 4 and x <= 11 and y >= 2 and y <= 13:
            if x == 4 or x == 11 or y == 2 or y == 13:
                pixels.append((30, 30, 30, 255)) # Case
            elif y == 12:
                pixels.append((50, 50, 50, 255)) # Screen bottom
            else:
                pixels.append((0, 150, 255, 255)) # Screen blue
        else:
            pixels.append((0, 0, 0, 0)) # Transparent
write_png("src/main/resources/assets/telecom/textures/item/smartphone.png", 16, 16, pixels)

print("Generated crisp 16x16 PNG textures.")
