import socket
import sys

def start_echo_client(host='127.0.0.1', port=7777):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            print(f"Connecting to {host}:{port}...")
            sock.connect((host, port))
            print("Connected! Type your message and hit Enter.")
            print("Type 'bye' to close the connection.")

            while True:
                message = input(">> ")
                if not message:
                    continue

                if message == "LOGIN":
                    login_frame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:meni\npasscode:films\n\n\0"
                    sock.sendall(login_frame.encode('utf-8'))

                # EchoServer uses LineMessageEncoderDecoder, which waits for '\n'
                # but StompServer uses StompEncDec, which waits for '\0' (null byte)
                sock.sendall((message + "\0").encode('utf-8'))

                if message.lower() == 'bye':
                    print("Closing...")
                    break

                data = sock.recv(1024)
                if not data:
                    print("Server disconnected.")
                    break
                
                print(f"Server echoed: {data.decode('utf-8').strip()}")

    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    port_to_use = int(sys.argv[1]) if len(sys.argv) > 1 else 7777
    start_echo_client(port=port_to_use)
