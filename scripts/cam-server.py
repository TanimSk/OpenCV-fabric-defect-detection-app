import asyncio
import websockets
import cv2
import numpy as np

HOST = "0.0.0.0"
PORT = 80
WS_ROUTE = "/ws"

async def send_frames(websocket):
    cap = cv2.VideoCapture(0)  # Open the default webcam
    if not cap.isOpened():
        print("Error: Could not open webcam.")
        return
    
    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                print("Error: Failed to capture frame.")
                break
            
            #frame = cv2.resize(frame, (320, 240))  # Reduce frame size
            _, buffer = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 50])  # Reduce quality
            frame_bytes = buffer.tobytes()  # Convert to bytes
            await websocket.send(frame_bytes)  # Send frame as bytes
            await asyncio.sleep(0.033)  # ~30 FPS
    except Exception as e:
        print(f"Error: {e}")
    finally:
        cap.release()

async def handler(websocket, path):
    if path == WS_ROUTE:
        print("Client connected")
        try:
            await send_frames(websocket)
        except Exception as e:
            print(f"Error: {e}")
        finally:
            print("Client disconnected")
    else:
        print(f"Invalid WebSocket route: {path}")
        await websocket.close()

async def main():
    server = await websockets.serve(handler, HOST, PORT)
    print(f"WebSocket server started at ws://your_ip:{PORT}{WS_ROUTE}")
    await server.wait_closed()

if __name__ == "__main__":
    asyncio.run(main())
