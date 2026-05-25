import os
import json

modid = "telecom"
assets = "src/main/resources/assets/telecom"

# Directories
block_models = f"{assets}/models/block"
item_models = f"{assets}/models/item"
blockstates = f"{assets}/blockstates"

def write_json(path, data):
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)

# Simple block models
for block in ["server", "router", "antenna"]:
    write_json(f"{block_models}/{block}.json", {
        "parent": "minecraft:block/cube_all",
        "textures": {
            "all": f"{modid}:block/{block}"
        }
    })
    write_json(f"{blockstates}/{block}.json", {
        "variants": {
            "": { "model": f"{modid}:block/{block}" }
        }
    })
    write_json(f"{item_models}/{block}.json", {
        "parent": f"{modid}:block/{block}"
    })

# Cable models
for cable in ["copper_cable", "fiber_cable"]:
    # Core
    write_json(f"{block_models}/{cable}_core.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "texture": f"{modid}:block/{cable}",
            "particle": f"{modid}:block/{cable}"
        },
        "elements": [
            {
                "from": [6, 6, 6],
                "to": [10, 10, 10],
                "faces": {
                    "north": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "east": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "south": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "west": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "up": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "down": {"uv": [6, 6, 10, 10], "texture": "#texture"}
                }
            }
        ]
    })
    # Straight (example template for parts)
    write_json(f"{block_models}/{cable}_part.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "texture": f"{modid}:block/{cable}",
            "particle": f"{modid}:block/{cable}"
        },
        "elements": [
            {
                "from": [6, 6, 0],
                "to": [10, 10, 6],
                "faces": {
                    "north": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "east": {"uv": [6, 0, 10, 6], "texture": "#texture"},
                    "south": {"uv": [6, 6, 10, 10], "texture": "#texture"},
                    "west": {"uv": [6, 0, 10, 6], "texture": "#texture"},
                    "up": {"uv": [6, 0, 10, 6], "texture": "#texture"},
                    "down": {"uv": [6, 0, 10, 6], "texture": "#texture"}
                }
            }
        ]
    })
    # Blockstate for cable
    write_json(f"{blockstates}/{cable}.json", {
        "multipart": [
            {
                "apply": { "model": f"{modid}:block/{cable}_core" }
            },
            {
                "when": { "north": "true" },
                "apply": { "model": f"{modid}:block/{cable}_part" }
            },
            {
                "when": { "east": "true" },
                "apply": { "model": f"{modid}:block/{cable}_part", "y": 90 }
            },
            {
                "when": { "south": "true" },
                "apply": { "model": f"{modid}:block/{cable}_part", "y": 180 }
            },
            {
                "when": { "west": "true" },
                "apply": { "model": f"{modid}:block/{cable}_part", "y": 270 }
            },
            {
                "when": { "up": "true" },
                "apply": { "model": f"{modid}:block/{cable}_part", "x": 270 }
            },
            {
                "when": { "down": "true" },
                "apply": { "model": f"{modid}:block/{cable}_part", "x": 90 }
            }
        ]
    })
    # Item for cable
    write_json(f"{item_models}/{cable}.json", {
        "parent": f"{modid}:block/{cable}_core"
    })

# Smartphone item
write_json(f"{item_models}/smartphone.json", {
    "parent": "minecraft:item/generated",
    "textures": {
        "layer0": f"{modid}:item/smartphone"
    }
})

print("Generated all JSONs successfully.")
