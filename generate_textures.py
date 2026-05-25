import os
from PIL import Image

output_dir = "src/main/resources/assets/telecom/textures/block"

# Copper: Brownish/Orange with green specks
copper = Image.new("RGBA", (16, 16), (184, 115, 51, 255))
pixels = copper.load()
for x in range(16):
    for y in range(16):
        if (x + y) % 5 == 0:
            pixels[x, y] = (100, 160, 120, 255) # Greenish oxidation
        elif (x * y) % 7 == 0:
            pixels[x, y] = (150, 90, 40, 255) # Darker brown
copper.save(os.path.join(output_dir, "copper_cable.png"))

# Small fiber: Yellow with blue core
fiber = Image.new("RGBA", (16, 16), (255, 215, 0, 255))
pixels = fiber.load()
for y in range(16):
    pixels[7, y] = (0, 200, 255, 255)
    pixels[8, y] = (0, 200, 255, 255)
fiber.save(os.path.join(output_dir, "fiber_cable.png"))

# Medium fiber: Black with yellow stripes
medium = Image.new("RGBA", (16, 16), (40, 40, 40, 255))
pixels = medium.load()
for x in range(16):
    for y in range(16):
        if x == 4 or x == 11:
            pixels[x, y] = (255, 215, 0, 255)
medium.save(os.path.join(output_dir, "medium_fiber_cable.png"))

# Big fiber: Dark grey with blue and yellow
big = Image.new("RGBA", (16, 16), (30, 30, 30, 255))
pixels = big.load()
for x in range(16):
    for y in range(16):
        if x == 3:
            pixels[x, y] = (0, 200, 255, 255)
        elif x == 12:
            pixels[x, y] = (255, 215, 0, 255)
big.save(os.path.join(output_dir, "big_fiber_cable.png"))

print("Textures generated successfully!")
