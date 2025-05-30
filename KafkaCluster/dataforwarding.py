import socket
import threading
import argparse

# --- Configuration ---
DEFAULT_LISTEN_HOST = '0.0.0.0'  # Listen on all available network interfaces
DEFAULT_LISTEN_PORT = 9091       # Port to listen on in your LAN
DEFAULT_VM_IP = '192.168.182.131'  # <<< CHANGE THIS to your VM's IP address
DEFAULT_VM_PORT = 9092         # <<< CHANGE THIS to your Kafka server's port in the VM
BUFFER_SIZE = 4096

def handle_client(client_socket, vm_ip, vm_port):
    """
    Handles a single client connection and forwards data to/from the VM.
    """
    vm_socket = None
    try:
        # Connect to the VM
        vm_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        vm_socket.connect((vm_ip, vm_port))
        print(f"[*] Connected to VM: {vm_ip}:{vm_port}")

        # Start threads for bidirectional data transfer
        def forward_data(src, dst, direction):
            try:
                while True:
                    data = src.recv(BUFFER_SIZE)
                    if not data:
                        print(f"[{direction}] Connection closed by {src.getpeername()}")
                        break
                    print(f"[{direction}] Received {len(data)} bytes from {src.getpeername()}")
                    dst.sendall(data)
                    print(f"[{direction}] Forwarded {len(data)} bytes to {dst.getpeername()}")
            except ConnectionResetError:
                print(f"[{direction}] Connection reset by {src.getpeername()}")
            except Exception as e:
                print(f"[{direction}] Error during data forwarding: {e}")
            finally:
                if hasattr(src, 'shutdown') and hasattr(src, 'close'):
                    try:
                        src.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass # Ignore if already closed or not connected
                    src.close()
                if hasattr(dst, 'shutdown') and hasattr(dst, 'close'):
                    try:
                        dst.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass # Ignore if already closed or not connected
                    dst.close()
                print(f"[{direction}] Sockets closed.")


        client_to_vm_thread = threading.Thread(target=forward_data, args=(client_socket, vm_socket, "Client->VM"))
        vm_to_client_thread = threading.Thread(target=forward_data, args=(vm_socket, client_socket, "VM->Client"))

        client_to_vm_thread.start()
        vm_to_client_thread.start()

        client_to_vm_thread.join()
        vm_to_client_thread.join()

    except ConnectionRefusedError:
        print(f"[!] Connection to VM {vm_ip}:{vm_port} refused. Ensure the service is running and accessible.")
    except socket.gaierror:
        print(f"[!] Could not resolve VM IP address: {vm_ip}. Check the IP.")
    except Exception as e:
        print(f"[!] Error in handle_client: {e}")
    finally:
        if client_socket:
            client_socket.close()
            print(f"[*] Closed connection from client: {client_socket.getpeername() if hasattr(client_socket, 'getpeername') else 'N/A'}")
        if vm_socket:
            vm_socket.close()
            print(f"[*] Closed connection to VM: {vm_ip}:{vm_port}")

def start_server(listen_host, listen_port, vm_ip, vm_port):
    """
    Starts the listening server.
    """
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1) # Allow reuse of address

    try:
        server_socket.bind((listen_host, listen_port))
        server_socket.listen(5)  # Max 5 pending connections
        print(f"[*] Listening on {listen_host}:{listen_port}")
        print(f"[*] Forwarding traffic to {vm_ip}:{vm_port}")

        while True:
            try:
                client_socket, addr = server_socket.accept()
                print(f"[*] Accepted connection from {addr[0]}:{addr[1]}")
                # Create a new thread to handle this client
                client_handler = threading.Thread(target=handle_client, args=(client_socket, vm_ip, vm_port))
                client_handler.start()
            except KeyboardInterrupt:
                print("\n[*] Server shutting down...")
                break
            except Exception as e:
                print(f"[!] Error accepting connection: {e}")
                if client_socket:
                    client_socket.close()

    except OSError as e:
        if e.errno == 98: # Address already in use
            print(f"[!] Error: Port {listen_port} is already in use on {listen_host}.")
        else:
            print(f"[!] Server socket error: {e}")
    except Exception as e:
        print(f"[!] An unexpected error occurred: {e}")
    finally:
        if server_socket:
            server_socket.close()
            print("[*] Server socket closed.")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Simple TCP Port Forwarder")
    parser.add_argument('--listen-host', type=str, default=DEFAULT_LISTEN_HOST,
                        help=f"Host to listen on (default: {DEFAULT_LISTEN_HOST})")
    parser.add_argument('--listen-port', type=int, default=DEFAULT_LISTEN_PORT,
                        help=f"Port to listen on (default: {DEFAULT_LISTEN_PORT})")
    parser.add_argument('--vm-ip', type=str, default=DEFAULT_VM_IP,
                        help=f"IP address of the VM to forward to (default: {DEFAULT_VM_IP})")
    parser.add_argument('--vm-port', type=int, default=DEFAULT_VM_PORT,
                        help=f"Port on the VM to forward to (default: {DEFAULT_VM_PORT})")

    args = parser.parse_args()

    try:
        start_server(args.listen_host, args.listen_port, args.vm_ip, args.vm_port)
    except KeyboardInterrupt:
        print("\n[*] Port forwarding stopped by user.")
    except Exception as e:
        print(f"[!] Failed to start port forwarder: {e}")