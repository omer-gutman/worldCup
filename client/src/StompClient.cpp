#include <iostream>
#include <string>
#include <vector>
#include <sstream>
#include <thread>
#include <atomic>
#include <map>
#include <mutex>
#include <queue>
#include <fstream>
#include <algorithm>
#include <chrono> //short break to lower the cpu usage
#include "ConnectionHandler.h"
#include "event.h"

//inorder to sync everything all of these global vars were used - thread safe
std::atomic<bool> shouldTerminate(false);// flag
std::atomic<bool> isLoggedIn(false);//still the same session?
std::mutex receiptMutex; // lock
std::map<int, std::string> receiptMap;// log for all the receipts
std::map<std::string, int> channelToSubId; //map channel id
std::queue<std::string> inputQueue;//to prevent thread being blocked by input
std::mutex inputMutex;//locking the queue
int receiptCounter = 0;
int subscriptionIdCounter = 0;
//Single event
struct EventReport {
    int time;
    std::string eventName;
    std::string description;
	bool beforeHalftime;
};
//Game report
struct GameUpdates {
    std::string teamA;
    std::string teamB;
    std::map<std::string, std::string> generalStats;
    std::map<std::string, std::string> teamAStats;
    std::map<std::string, std::string> teamBStats;
	//Event list
    std::vector<EventReport> events; 
};
std::string activeUser = "";
std::mutex memoryMutex;
std::map<std::string, std::map<std::string, GameUpdates>> memoryStorage;
//stomp proctocl functions used to build the frames properly and refactor the code
//sends a subscribe frame
void sendJoin(ConnectionHandler* handler, const std::string& destination) {
    int rId, sId;
	//thread safe
    {
        std::lock_guard<std::mutex> lock(receiptMutex);
        rId = receiptCounter++;
        sId = subscriptionIdCounter++;
        receiptMap[rId] = "Joined channel " + destination;
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
void sendUnsubscribe(ConnectionHandler* handler, int subId, const std::string& destination) {
    int rId;
    {
        std::lock_guard<std::mutex> lock(receiptMutex);
        rId = receiptCounter++;
        receiptMap[rId] = "Exited channel " + destination; 
    }
    std::string frame = "UNSUBSCRIBE\nid:" + std::to_string(subId) + "\nreceipt:" + std::to_string(rId) + "\n\n";
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
                std::cout << action << std::endl;
                if (action == "DISCONNECT") isLoggedIn = false; //flags termination
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

        // decrypt the message
        std::string destination = headers["destination"];
        // string cleaning
        if (!destination.empty() && destination[0] == '/') {
            destination = destination.substr(1);
        }

        std::string reportingUser, teamA, teamB, eventName, description;
        int time = 0;
        int currentUpdateSection = 0; // 0 = none, 1 = general, 2 = teamA, 3 = teamB
        
        std::stringstream bodyStream(bodyPart);
        std::string bodyLine;
        
        // reading every line
        while (std::getline(bodyStream, bodyLine)) {
            if (bodyLine.empty()) continue;

            size_t pos = bodyLine.find(':');
            if (pos != std::string::npos) {
                std::string key = bodyLine.substr(0, pos);
                std::string value = bodyLine.substr(pos + 1);
                
                // clearing unwanted spaces
                if (!value.empty() && value[0] == ' ') value.erase(0, 1);

                if (key == "user") reportingUser = value;
                else if (key == "team a") teamA = value;
                else if (key == "team b") teamB = value;
                else if (key == "event name") eventName = value;
                else if (key == "time") time = std::stoi(value);
                else if (key == "general game updates") currentUpdateSection = 1;
                else if (key == "team a updates") currentUpdateSection = 2;
                else if (key == "team b updates") currentUpdateSection = 3;
                else if (key == "description") {
                    // reading the rest of the lines
                    std::string descLine;
                    while (std::getline(bodyStream, descLine)) {
                        if (descLine == "\0") break; 
                        description += descLine + "\n";
                    }
                    break; // description is always the end of the message
                }
                else {
                    // updated stats
                    std::lock_guard<std::mutex> lock(memoryMutex);
                    GameUpdates& gameData = memoryStorage[destination][reportingUser];
                    gameData.teamA = teamA;
                    gameData.teamB = teamB;
                    
                    if (currentUpdateSection == 1) gameData.generalStats[key] = value;
                    else if (currentUpdateSection == 2) gameData.teamAStats[key] = value;
                    else if (currentUpdateSection == 3) gameData.teamBStats[key] = value;
                }
            }
        }

        // Half time flag
        bool isBeforeHalftime = true; 
        {
            std::lock_guard<std::mutex> lock(memoryMutex);
            GameUpdates& gameData = memoryStorage[destination][reportingUser];
            if (gameData.generalStats.count("before halftime")) {
                isBeforeHalftime = (gameData.generalStats["before halftime"] == "true");
            }
            // Saving the last update
            EventReport newEvent = {time, eventName, description, isBeforeHalftime};
            gameData.events.push_back(newEvent);
        }
    }
}

//parsing everyhing is it error or is it a receipt only the listener knows
void runListener(ConnectionHandler* handler) {//the listener thread
    while (!shouldTerminate && isLoggedIn) {
        std::string response;
        if (!handler->getFrameAscii(response, '\0')) {
            std::cout << "Disconnected from server" << std::endl;
			isLoggedIn = false;
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
			isLoggedIn = false;
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
        } else {
            shouldTerminate = true; 
        }
    }
}

int main(int argc, char *argv[]) {
	std::thread inputThread(readFromCin);
    inputThread.detach();
	ConnectionHandler* handler = nullptr;
    while (!shouldTerminate) {
        std::string line;
		{
            std::lock_guard<std::mutex> lock(inputMutex);
            if (!inputQueue.empty()) {
                line = inputQueue.front();
                inputQueue.pop();
            }
        }
        if (!line.empty()) {
            std::stringstream ss(line);
            std::string command;
            ss >> command; // extracting the first word

        if (command == "login") {
			if (isLoggedIn || handler != nullptr) {
                    std::cout << "The client is already logged in, log out before trying again" << std::endl;
                    continue;
            }
			std::string address, username, password;
            if (!(ss >> address >> username >> password)) {
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
					activeUser = username;
					isLoggedIn = true;
					channelToSubId.clear();
					std::thread listenerThread(runListener, handler); // starting the listener thread
					listenerThread.detach(); // running on his own
                } else {
                    // couldn't connect
                    std::cout << "Login failed: " << response << std::endl;
                    handler->close();
                    delete handler;
                    handler = nullptr;
                }
            }
            else if (!isLoggedIn) {
                std::cout << "You must login first" << std::endl;
            }
            else if (command == "logout") {
				sendDisconnect(handler);
            } 
            else if (command == "join") {
                std::string destination;
                if (ss >> destination) {
                    sendJoin(handler, destination); 
                }
            }
			else if (command == "report") {
                std::string file_name;
			    // Extract the file name from the user's command
                if (ss >> file_name) {
                    try {
                        names_and_events nne = parseEventsFile(file_name);
                        std::string game_name = nne.team_a_name + "_" + nne.team_b_name;            
                        for (const auto& ev : nne.events) {
			                //Save the event in the client's local memory
			                {
			                    // Prevent race conditions if the listener thread tries to write at the same time
			                    std::lock_guard<std::mutex> lock(memoryMutex);
			                    // Retrieve the specific game and user from the memory dictionary
			                    GameUpdates& data = memoryStorage[game_name][activeUser];
			                    data.teamA = nne.team_a_name; // Save team A's name
			                    data.teamB = nne.team_b_name; // Save team B's name
			                    // Add general updates to the accumulative dictionary
			                    for (const auto& pair : ev.get_game_updates()) {
			                        data.generalStats[pair.first] = pair.second;
			                    }
			                    // Add team A's updates to its dictionary
			                    for (const auto& pair : ev.get_team_a_updates()) {
			                        data.teamAStats[pair.first] = pair.second;
			                    }
			                    // Add team B's updates to its dictionary
			                    for (const auto& pair : ev.get_team_b_updates()) {
			                        data.teamBStats[pair.first] = pair.second;
			                    }
			                    // Create a small record that only saves the event details
			                    EventReport newReport = {ev.get_time(), ev.get_name(), ev.get_discription()};
			                    data.events.push_back(newReport); // Add the record to the game's event list
			                }
			                // Start the message
			                std::string frame = "SEND\ndestination:/" + game_name + "\n\n";
			                // Add the event information
			                frame += "user: " + activeUser + "\n";
			                frame += "team a: " + nne.team_a_name + "\n";
			                frame += "team b: " + nne.team_b_name + "\n";
			                frame += "event name: " + ev.get_name() + "\n";
			                frame += "time: " + std::to_string(ev.get_time()) + "\n";  
			                // Concatenate the general stats that changed specifically in this event (from the JSON)
			                frame += "general game updates:\n";
			                for (const auto& pair : ev.get_game_updates()) {
			                    frame += pair.first + ": " + pair.second + "\n";
			                }
			                // Concatenate team A's stats
			                frame += "team a updates:\n";
			                for (const auto& pair : ev.get_team_a_updates()) {
			                    frame += pair.first + ": " + pair.second + "\n";
			                }
			                // Concatenate team B's stats
			                frame += "team b updates:\n";
			                for (const auto& pair : ev.get_team_b_updates()) {
			                    frame += pair.first + ": " + pair.second + "\n";
			                }
			                // Concatenate the event's description (always comes last)
			                frame += "description:\n" + ev.get_discription() + "\n";
			                // Send the ready Frame to the server
			                handler->sendFrameAscii(frame, '\0');
			            }
			        } catch (const std::exception& e) {
			            // In case the file doesn't exist, is invalid, or parsing fails
			            std::cout << "Error parsing file: " << e.what() << std::endl;
			        }
			    }
			}
			else if (command == "exit") { 
				std::string destination;
				if (ss >> destination) {
					if (channelToSubId.count(destination)) {//unsubscribes from a channel
						sendUnsubscribe(handler, channelToSubId[destination], destination);
            			channelToSubId.erase(destination);//deletes from the map
					} else {
						std::cout << "Error: Not subscribed to channel " << destination << std::endl;
					}
				}
			}
			else if (command == "summary") {
			    std::string game_name, user, file_name;
			    // extracting parm from the command
			    if (ss >> game_name >> user >> file_name) {
			        
			        std::lock_guard<std::mutex> lock(memoryMutex);
			        
			        // Checking for existance
			        if (memoryStorage.count(game_name) && memoryStorage[game_name].count(user)) {
			            // pulling the data
			            GameUpdates& data = memoryStorage[game_name][user];
			            
			            // Creating new file for writing or using existing one
			            std::ofstream outfile(file_name);
			            if (!outfile.is_open()) {
			                std::cout << "Error: Could not open or create file " << file_name << std::endl;
			                continue;
			            }
			            
			            // Writing title
			            outfile << data.teamA << " vs " << data.teamB << "\n";
			            outfile << "Game stats:\n";
			            
			            outfile << "General stats:\n";
			            for (const auto& pair : data.generalStats) {
			                outfile << pair.first << ": " << pair.second << "\n";
			            }
			            
			            // Stats team A
			            outfile << data.teamA << " stats:\n";
			            for (const auto& pair : data.teamAStats) {
			                outfile << pair.first << ": " << pair.second << "\n";
			            }
			            
			            // Stats team B
			            outfile << data.teamB << " stats:\n";
			            for (const auto& pair : data.teamBStats) {
			                outfile << pair.first << ": " << pair.second << "\n";
			            }
			            
			            outfile << "Game event reports:\n";
			            
			            // Copying the list of events
			            std::vector<EventReport> sortedEvents = data.events;
			            
			            // Sorting events by time
			            std::sort(sortedEvents.begin(), sortedEvents.end(), [](const EventReport& a, const EventReport& b) {
                            if (a.beforeHalftime != b.beforeHalftime) {
                                return a.beforeHalftime; 
                            }
                            return a.time < b.time;
                        });
			            
			            // Printing sorted events
			            for (const auto& ev : sortedEvents) {
			                outfile << ev.time << " " << ev.eventName << ":\n\n";
			                outfile << ev.description << "\n\n"; 
			            }
			            
			            outfile.close();			            
			        } else {
			            std::cout << "No data found for game " << game_name << " reported by user " << user << std::endl;
			        }
			    } else {
			        std::cout << "Error: Invalid summary command format. Usage: summary {game_name} {user} {file}" << std::endl;
			    }
			}
		}
		if (handler != nullptr && !isLoggedIn) {
            handler->close();
            delete handler;
            handler = nullptr;
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
