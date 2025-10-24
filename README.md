# queue
A simple queue mod for Minecraft Java Edition. Lightweight, works on essentially any server types, (Spigot/Bukkit/Fabric/Paper), Tiered Priority Queue, and simple at its core.

## How it works & What you need.
**Requirements**:
- A Velocity Proxy Server
  - Permission System (_Optional but required for things like priority queue and admin account bypasses_ **LuckPerms Velocity Reccomended**).
  - The Queue plugin installed on the velocity server.
- Your Main Server:
  - This can be any type of Minecraft Server, **as long as it connects to the velocity proxy it will work**.
  - Fabric Proxy Lite is reccomended for **fabric servers.**
- The Queue Server
  - This can be any type of Minecraft Server, **as long as it connects to the velocity proxy it will work**.
  - If you don't want to deploy yet another heavy load Minecraft Server it is reccomended to use [https://github.com/Quozul/PicoLimbo](url) or [https://github.com/Nan1t/NanoLimbo](url).
 
**Configuration:**
- `server-config.yml`:
  ```
  {main-server: main, queue-server: queue, main-server-max-players: 160}
  ```
  - The `main-server` is the server that players not in queue will play on, the name of it is the name defined in you velocity proxy.
  - The `queue-server` is the server that players in queue will wait in, the name of it is the name defined in you velocity proxy.
  - The `main-server-max-players` is the maximum amount of players can be on the server before players are sent to wait in the queue.
> [!WARNING]
> On your actual main server `server.properities` file it is recomended that you have 5-10 more slots that your max players defined in the configuration file above to ensure that admins can bypass the queue even when the main server is full.
- `queue-points.yml`:
  ```
  tiers: {queue.vip.diamond: 10, queue.vip.gold: 20, queue.vip.silver: 30, queue.vip: 45}
  default-seconds: 60
  ```
  - The `tiers` allows you to define the amount of seconds per point each rank gets, if a player contains one of these permission nodes the tier config will be applied to them. (You can add more permission nodes too)
  - The `default-seconds` is for players without a priority tier, this is the default for players without a setup rank.
  
