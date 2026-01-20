#include <iostream>
#include <string>
#include <vector>
#include <sstream>
#include <thread>
#include <atomic>
#include <map>
#include <mutex>
#include <queue>
#include <chrono> //short break to lower the cpu usage
#include "ConnectionHandler.h"

//inorder to sync everything all of these global vars were used - thread safe
std::atomic<bool> shouldTerminate(false);// flag
std::mutex receiptMutex; // lock
std::map<int, std::string> receiptMap;// log for all the receipts
std::map<std::string, int> channelToSubId; //map channel id
std::queue<std::string> inputQueue;//to prevent thread being blocked by input
std::mutex inputMutex;//locking the queue
int receiptCounter = 0;
int subscriptionIdCounter = 0;

//stomp proctocl functions used to build the frames properly and refactor the code
//sends a subscribe frame
void sendJoin(ConnectionHandler* handler, const std::string& destination) {
    int rId, sId;
	//thread safe
    {
        std::lock_guard<std::mutex> lock(receiptMutex);
        rId = receiptCounter++;
        sId = subscriptionIdCounter++;
        receiptMap[rId] = "Joined channel: " + destination;
        channelToSubId[destination] = sId;
    }
    std::string frame = "SUBSCRIBE\ndestination:" + destination + "\nid:" + std::to_string(sId) + "\nreceipt:" + std::to_string(rId) + "\nack:client-individual\n\n";//the frame
    handler->sendFrameAscii(frame, '\0');
}

//sends a send frame with message body
void sendMsg(ConnectionHandler* handler, const std::string& destination, const std::string& message) {
    std::string frame = "SEND\ndestination:" + destination + "\n\n" + message;
    handler->sendFrameAscii(frame, '\0');
}
//sends an unsubscribe frame
void sendUnsubscribe(ConnectionHandler* handler, int subId) {
    std::string frame = "UNSUBSCRIBE\nid:" + std::to_string(subId) + "\n\n";
    handler->sendFrameAscii(frame, '\0');
}

// sends a disconnect frame and requests a receipt to confirm safe closure
void sendDisconnect(ConnectionHandler* handler) {
    int rId;
    {
        std::lock_guard<std::mutex> lock(receiptMutex);
        rId = receiptCounter++;
        receiptMap[rId] = "DISCONNECT";
    }
    std::string frame = "DISCONNECT\nreceipt:" + std::to_string(rId) + "\n\n";
    handler->sendFrameAscii(frame, '\0');
}

//parses frames and triggers actions based on the receipt ID
void handleReceipt(const std::string& headersPart) {
    std::stringstream ss(headersPart);
    std::string line;
    while (std::getline(ss, line) && !line.empty()) {
        size_t pos = line.find(':');
        if (pos != std::string::npos && line.substr(0, pos) == "receipt-id") {
            int rId = std::stoi(line.substr(pos + 1));
            std::lock_guard<std::mutex> lock(receiptMutex);
            if (receiptMap.count(rId)) {
                std::string action = receiptMap[rId];
                std::cout << "Confirmed: " << action << std::endl;
                if (action == "DISCONNECT") shouldTerminate = true; //flags termination
                receiptMap.erase(rId);
            }
        }
    }
}



//process the message
void processMessage(const std::string& headersPart, const std::string& bodyPart, ConnectionHandler* handler) {
    std::map<std::string, std::string> headers;
    std::stringstream ss(headersPart);
    std::string line;
    while (std::getline(ss, line) && !line.empty()) {
        size_t pos = line.find(':');
        if (pos != std::string::npos) {
            headers[line.substr(0, pos)] = line.substr(pos + 1);
        }
    }
	// sends an ACK frame if the server provided an ack header
    if (headers.count("ack")) {
        std::string ackFrame = "ACK\nid:" + headers["ack"] + "\n\n";
        handler->sendFrameAscii(ackFrame, '\0');
    }
	//displays the message content to the user
    if (headers.count("destination")) {
        std::cout << "\n--- New Message from " << headers["destination"] << " ---" << std::endl;
        std::cout << bodyPart << std::endl;
        std::cout << "-----------------------------------" << std::endl;
    }
}

//parsing everyhing is it error or is it a receipt only the listener knows
void runListener(ConnectionHandler* handler) {//the listener thread
    while (!shouldTerminate) {
        std::string response;
        if (!handler->getFrameAscii(response, '\0')) {
            std::cout << "Disconnected from server" << std::endl;
            break; 
        }
		size_t firstNewline = response.find('\n');//first time new line appears
        if (firstNewline == std::string::npos) continue; //continue if found

		std::string command = response.substr(0, firstNewline);// extracting the command
		std::string rest = response.substr(firstNewline + 1); //the rest of the frame

		size_t bodyStart = rest.find("\n\n");//finding the end of the body
        std::string headersPart;
        std::string bodyPart;

		if (bodyStart != std::string::npos) {
            headersPart = rest.substr(0, bodyStart); // all the headers
            bodyPart = rest.substr(bodyStart + 2);  // the body
        } else {
            headersPart = rest;//in case there isn't body 
        }

		if (command == "MESSAGE") {
			processMessage(headersPart, bodyPart, handler);
		}
        else if (command == "RECEIPT") {
            handleReceipt(headersPart);
        }
		else if (command == "ERROR") {
            std::cout << "Server Error: " << bodyPart << std::endl;
			shouldTerminate = true;
        }
    }
}

// simple get line can block  making input a different thread solved the being blocked problem
void readFromCin() {//reading in a non blocking way
    while (!shouldTerminate) {
        std::string line;
        if (std::getline(std::cin, line)) {
            std::lock_guard<std::mutex> lock(inputMutex);
            inputQueue.push(line);
        }
    }
}

int main(int argc, char *argv[]) {
	ConnectionHandler* handler = nullptr;
    while (true) {
        std::string line;
        if (!std::getline(std::cin, line)) return 0; // getting user input
        std::stringstream ss(line);
        std::string command;
        ss >> command; // extracting the first word

        if (command == "login") {
            std::string address, username, password;
			if (!(ss >> address >> username >> password)) {// extracting the rest
                std::cout << "Error: Missing login arguments" << std::endl;
                continue;
            }
			size_t pos = address.find(":"); // searching for : in the string
			if (pos == std::string::npos) {
                std::cout << "Error: Invalid address format (host:port)" << std::endl;
                continue;
            }
            std::string host = address.substr(0, pos); //if found will split to ip address and convert the str port to int
            short port = static_cast<short>(std::stoi(address.substr(pos + 1)));

			handler = new ConnectionHandler(host, port);
			if (!handler->connect()) {//trying to connect
                std::cout << "Could not connect to server" << std::endl; 
                delete handler;
                handler = nullptr;
                continue;
            }

			//the conection frame according to the example
			std::string connectFrame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:" + username + "\npasscode:" + password + "\n\n";
            handler->sendFrameAscii(connectFrame, "\0");

			std::string response;
            if (handler->getFrameAscii(response, '\0')) {
                if (response.find("CONNECTED") != std::string::npos) {
                    std::cout << "Login successful" << std::endl;

					std::thread listenerThread(runListener, handler); // starting the listener thread
					listenerThread.detach(); // running on his own
					std::thread inputThread(readFromCin);
    				inputThread.detach();
                    break; // exiting the loop the user connected succssesfully
                } else {
                    // couldn't connect
                    std::cout << "Login failed: " << response << std::endl;
                    handler->close();
                    delete handler;
                    handler = nullptr;
                }
            }
        } else {
            std::cout << "You must login first" << std::endl;
        }
    }
	//the engine syncing 3 threads, running STOMP protocol, state managment and dealing with blocking I/Os
	while (!shouldTerminate) {//after successfully login in 
        std::string line = "";
		//checking for message in queue
		{
            std::lock_guard<std::mutex> lock(inputMutex);
            if (!inputQueue.empty()) {
                line = inputQueue.front();
                inputQueue.pop();
            }
        }
        if (!line.empty()){
			std::stringstream ss(line);
			std::string command;
			ss >> command;
			if (command == "logout") {
            sendDisconnect(handler);
			} 
			else if (command == "join") {
				std::string destination;
				if (ss >> destination) {
					sendJoin(handler, destination); 
				}
			}
			else if (command == "send") {
				std::string destination;
				ss >> destination; //extract the destination
				
				std::string message;
				std::getline(ss, message); // the rest of the message
				if (!message.empty() && message[0] == ' ') message.erase(0, 1); //deletes the space at the beginning
				
				std::string sendFrame = "SEND\ndestination:" + destination + "\n\n" + message;//creating the new frame

				handler->sendFrameAscii(sendFrame, '\0');//send to the server
			}
			else if (command == "exit") { 
				std::string destination;
				if (ss >> destination) {
					if (channelToSubId.count(destination)) {//unsubscribes from a channel
					sendUnsubscribe(handler, channelToSubId[destination]);
					channelToSubId.erase(destination);//deletes from the map
					} else {
						std::cout << "Error: Not subscribed to channel " << destination << std::endl;
					}
				}
			}
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(10)); //lowering the cpu usage
    }

    //exiting and deleting cleaning everything
    if (handler) {
        handler->close(); //closing the socket
        delete handler;
    }
    
    std::cout << "Exiting application..." << std::endl;
    return 0;
}