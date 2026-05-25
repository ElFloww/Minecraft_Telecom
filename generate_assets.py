import os
import json

base_dir = "src/main/resources/assets/telecom/"
os.makedirs(base_dir + "blockstates", exist_ok=True)
os.makedirs(base_dir + "models/block", exist_ok=True)
os.makedirs(base_dir + "models/item", exist_ok=True)

hubs = ["nro", "nra", "pm", "sr"]
for h in hubs:
    # blockstate
    with open(base_dir + f"blockstates/{h}.json", "w") as f:
        json.dump({"variants": {"": {"model": f"telecom:block/{h}"}}}, f)
    # block model
    with open(base_dir + f"models/block/{h}.json", "w") as f:
        json.dump({"parent": "minecraft:block/cube_all", "textures": {"all": f"telecom:block/{h}"}}, f)
    # item model
    with open(base_dir + f"models/item/{h}.json", "w") as f:
        json.dump({"parent": f"telecom:block/{h}"}, f)

cables = ["medium_fiber_cable", "big_fiber_cable"]
parts = ["core", "up", "down", "north", "south", "east", "west"]
for c in cables:
    # block model parts
    for p in parts:
        with open(base_dir + f"models/block/{c}_{p}.json", "w") as f:
            json.dump({"parent": f"telecom:block/cable_{p}", "textures": {"cable": f"telecom:block/{c}"}}, f)
    # item model
    with open(base_dir + f"models/item/{c}.json", "w") as f:
        json.dump({"parent": f"telecom:block/{c}_core"}, f)

