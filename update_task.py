import sys

with open("/Users/florentdubut/.gemini/antigravity/brain/3c263116-6a80-4011-9d49-48c6fcad7830/task.md") as f:
    content = f.read()

content = content.replace("[ ]", "[x]")

with open("/Users/florentdubut/.gemini/antigravity/brain/3c263116-6a80-4011-9d49-48c6fcad7830/task.md", "w") as f:
    f.write(content)
