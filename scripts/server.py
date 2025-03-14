import asyncio
import websockets
import os

IMAGE_PATH = "image.jpg"  # Change this to your image file path
HOST = "0.0.0.0"
PORT = 8765

async def send_image(websocket):
    if not os.path.exists(IMAGE_PATH):
        print(f"Error: {IMAGE_PATH} not found")
        return
    
    while True:
        with open(IMAGE_PATH, "rb") as img_file:
            image_bytes = img_file.read()
        
        await websocket.send(image_bytes)
        print(f"Sent image ({len(image_bytes)} bytes) to client")
        await asyncio.sleep(1)  # Adjust delay as needed

async def handler(websocket):  # Removed 'path' for newer websockets versions
    print("Client connected")
    try:
        await send_image(websocket)
    except Exception as e:
        print(f"Error: {e}")
    finally:
        print("Client disconnected")

async def main():
    server = await websockets.serve(handler, HOST, PORT)
    print(f"WebSocket server started at ws://{HOST}:{PORT}")
    await server.wait_closed()

if __name__ == "__main__":
    asyncio.run(main())