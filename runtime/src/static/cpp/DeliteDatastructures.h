#ifndef __DELITE_DATASTRUCTURES_H__
#define __DELITE_DATASTRUCTURES_H__

// structure to keep thread-local resource information
typedef struct {
  int threadId;
  int numThreads;
  int socketId;
  int numSockets;
} resourceInfo_t;

#endif
