package my_mod;


public interface IPlatform {
    /** 获取方块操作助手 */
    IBlockHelper block();
    
    /** 获取物品与数据操作助手 */
    IItemHelper item();
    
    /** 判断当前是否为客户端环境 (解决 1.12-1.21 服务端/客户端判断差异) */
    boolean isClientSide(Object level);
    
    /** 向玩家发送系统消息 */
    void sendMessage(Object player, String message);
}

