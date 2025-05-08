from PIL import Image, ImageDraw
import os

# Define icon sizes for each density
densities = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

# Create directory for each density if it doesn't exist
for density in densities:
    os.makedirs(f"app/src/main/res/mipmap-{density}", exist_ok=True)

# Generate icons for each density
for density, size in densities.items():
    # Create square icon (standard)
    square_img = Image.new('RGB', (size, size), color='#4285F4')  # Google Blue color
    square_img.save(f"app/src/main/res/mipmap-{density}/ic_launcher.png")
    
    # Create round icon
    round_img = Image.new('RGBA', (size, size), color=(0, 0, 0, 0))
    draw = ImageDraw.Draw(round_img)
    draw.ellipse((0, 0, size, size), fill='#EA4335')  # Google Red color
    round_img.save(f"app/src/main/res/mipmap-{density}/ic_launcher_round.png")

print("Icons generated successfully!")