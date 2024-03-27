//:: cases StaticSharedCuda
//:: tool silicon
//:: verdict Pass

#include <cuda.h>

/*@
  context blockDim.x > 1 && blockDim.y == 1 && blockDim.z == 1;
  context gridDim.x > 0 && gridDim.y == 1 && gridDim.z == 1;

  context in != NULL && out != NULL;
  context \pointer_length(in) == 1;
  context \pointer_length(out) == n;
  context n > 0;
  context blockDim.x * gridDim.x >= n;
  context Perm(&in[0], write \ (blockDim.x * gridDim.x));
  context \gtid<n ==> Perm(&out[\gtid], write);

  requires \ltid == 0 ==> Perm(&s[0], write);

  ensures \gtid<n ==> out[\gtid] == \old(out[\gtid]) + in[0];
@*/
__global__ void blur_x(int* in, int* out, int n) {
  __shared__ int s[1];
  int tid = blockIdx.x * blockDim.x + threadIdx.x;
  if(threadIdx.x == 0) {
    s[threadIdx.x] = in[0];
  }

  /*@
    context Perm(&in[0], write \ (blockDim.x * gridDim.x));
    context tid<n ==> Perm(&out[tid], write);
    context tid<n ==> \old(out[tid]) == out[tid];

    requires threadIdx.x == 0 ==> Perm(&s[0], write);
    requires threadIdx.x == 0 ==> s[0] == in[0];

    ensures Perm(&s[0], write \ blockDim.x);

    ensures s[0] == in[0];
  @*/
  __syncthreads();

  if(tid < n) {
    out[tid] += s[0];
  }
}