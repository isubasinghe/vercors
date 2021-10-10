parser grammar LangGPGPUParser;

gpgpuLocalBarrier
    : valEmbedContract? GPGPU_BARRIER '(' GPGPU_LOCAL_BARRIER ')'
    ;

gpgpuGlobalBarrier
    : valEmbedContract? GPGPU_BARRIER '(' GPGPU_GLOBAL_BARRIER ')'
    ;

gpgpuCudaKernelInvocation
    : valEmbedGiven? clangIdentifier GPGPU_CUDA_OPEN_EXEC_CONFIG expression ',' expression GPGPU_CUDA_CLOSE_EXEC_CONFIG '(' argumentExpressionList ')' valEmbedYields?
    ;

gpgpuAtomicBlock
    : valEmbedWith? GPGPU_ATOMIC compoundStatement valEmbedThen?
    ;

gpgpuKernelSpecifier: GPGPU_KERNEL;