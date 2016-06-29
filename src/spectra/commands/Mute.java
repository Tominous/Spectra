/*
 * Copyright 2016 John Grosh (jagrosh).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spectra.commands;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.dv8tion.jda.Permission;
import net.dv8tion.jda.entities.Role;
import net.dv8tion.jda.entities.User;
import net.dv8tion.jda.events.message.MessageReceivedEvent;
import net.dv8tion.jda.utils.PermissionUtil;
import spectra.Argument;
import spectra.Command;
import spectra.FeedHandler;
import spectra.PermLevel;
import spectra.Sender;
import spectra.SpConst;
import spectra.datasources.Feeds;
import spectra.datasources.Mutes;
import spectra.datasources.Settings;
import spectra.utils.FormatUtil;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class Mute extends Command {
    final FeedHandler handler;
    final Settings settings;
    final Mutes mutes;
    public Mute(FeedHandler handler, Settings settings, Mutes mutes)
    {
        this.handler = handler;
        this.settings = settings;
        this.mutes = mutes;
        this.command = "mute";
        this.help = "mutes the specified user for the given time";
        this.availableInDM = false;
        this.level = PermLevel.MODERATOR;
        this.separatorRegex = "\\s+for\\s+";
        this.arguments = new Argument[]{
            new Argument("username",Argument.Type.LOCALUSER,true),
            new Argument("for <time>", Argument.Type.TIME, true,0,43200),
            new Argument("for <reason>", Argument.Type.LONGSTRING,false)
        };
        this.children = new Command[]{
            new MuteList()
        };
        this.requiredPermissions = new Permission[]{
            Permission.MANAGE_ROLES
        };
    }
    @Override
    protected boolean execute(Object[] args, MessageReceivedEvent event) {
        User target = (User)(args[0]);
        long seconds = (long)(args[1]);
        String reason = args[2]==null?null:(String)(args[2]);
        if(reason==null)
            reason = "[no reason specified]";
        PermLevel targetLevel = PermLevel.getPermLevelForUser(target, event.getGuild(), settings.getSettingsForGuild(event.getGuild().getId()));
        //make sure a Muted role exists
        Role mutedrole = null;
        for(Role role : event.getGuild().getRoles())
            if(role.getName().equalsIgnoreCase("muted"))
            {
                mutedrole = role; break;
            }
        if(mutedrole==null)
        {
            Sender.sendResponse(SpConst.WARNING+"No \"Muted\" role exists! Please add and setup up a \"Muted\" role, or use `"
                    +SpConst.PREFIX+"mute setup` to have one made automatically.", event);
            return false;
        }
        
        //check perm level of other user
        if(targetLevel.isAtLeast(level))
        {
            Sender.sendResponse(SpConst.WARNING+"**"+target.getUsername()+"** cannot be muted because they are listed as "+targetLevel, event);
            return false;
        }
        
        //check if bot can interact with the other user
        if(!PermissionUtil.canInteract(event.getJDA().getSelfInfo(), target, event.getGuild()))
        {
            Sender.sendResponse(SpConst.WARNING+"I cannot mute **"+target.getUsername()+"** due to permission hierarchy", event);
            return false;
        }
        
        //check if can interact with muted role
        if(!PermissionUtil.canInteract(event.getJDA().getSelfInfo(), mutedrole))
        {
            Sender.sendResponse(SpConst.WARNING+"I cannot mute **"+target.getUsername()+"** because the \"Muted\" role is above my highest role!", event);
            return false;
        }
        
        //attempt to mute
        try{
            event.getGuild().getManager().addRoleToUser(target, mutedrole).update();
            mutes.set(new String[]{target.getId(),event.getGuild().getId(),OffsetDateTime.now().plusSeconds(seconds).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)});
            Sender.sendResponse(SpConst.SUCCESS+"**"+target.getUsername()+"** was muted for "+FormatUtil.secondsToTime(seconds), event);
            handler.submitText(Feeds.Type.MODLOG, event.getGuild(), 
                    "\uD83D\uDD07 **"+event.getAuthor().getUsername()+"** muted **"+target.getUsername()+"** (ID:"+target.getId()+") for "+FormatUtil.secondsToTime(seconds)+" for "+reason);
            return true;
        }catch(Exception e)
        {
            Sender.sendResponse(SpConst.ERROR+"Failed to mute **"+target.getUsername()+"**", event);
            return false;
        }
    }
    
    private class MuteList extends Command
    {
        public MuteList()
        {
            this.command = "list";
            this.help = "lists users with a mute on the server";
            this.availableInDM = false;
            this.level = PermLevel.MODERATOR;
        }
        @Override
        protected boolean execute(Object[] args, MessageReceivedEvent event) {
            //make sure a Muted role exists
            Role mutedrole = null;
            for(Role role : event.getGuild().getRoles())
                if(role.getName().equalsIgnoreCase("muted"))
                {
                    mutedrole = role; break;
                }
            if(mutedrole==null)
            {
                Sender.sendResponse(SpConst.WARNING+"No \"Muted\" role exists! Please add and setup up a \"Muted\" role, or use `"
                        +SpConst.PREFIX+"mute setup` to have one made automatically.", event);
                return false;
            }
            List<User> list = event.getGuild().getUsersWithRole(mutedrole);
            List<String[]> list2 = mutes.getMutesForGuild(event.getGuild().getId());
            StringBuilder builder = new StringBuilder();
            int count = list.size();
            list.stream().map((u) -> {
                builder.append("\n**").append(u.getUsername()).append("** (ID:").append(u.getId()).append(")");
                return u;
            }).map((u) -> mutes.getMute(u.getId(), event.getGuild().getId())).filter((savedmute) -> (savedmute!=null)).forEach((savedmute) -> {
                builder.append(" ends in ").append(FormatUtil.secondsToTime(OffsetDateTime.now().until(OffsetDateTime.parse(savedmute[Mutes.UNMUTETIME]), ChronoUnit.SECONDS)));
            });
            for(String[] savedmute : list2)
            {
                User u = event.getJDA().getUserById(savedmute[Mutes.USERID]);
                if(u==null || !event.getGuild().isMember(u))
                {
                    count++;
                    builder.append("ID:").append(savedmute[Mutes.USERID]).append(" ends in ").append(FormatUtil.secondsToTime(OffsetDateTime.now().until(OffsetDateTime.parse(savedmute[Mutes.UNMUTETIME]), ChronoUnit.SECONDS)));
                }
            }
            Sender.sendResponse(SpConst.SUCCESS+"**"+count+"** users muted on **"+event.getGuild().getName()+"**:"+builder.toString(), event);
            return true;
        }
    }
}
