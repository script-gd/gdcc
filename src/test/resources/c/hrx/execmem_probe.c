int main(void) {
    if (!gdcc_hrx_execmem_probe()) return 10;
    printf("OK execmem\n");
    return 0;
}
