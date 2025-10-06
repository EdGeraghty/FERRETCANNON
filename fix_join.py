import re

# Read the file
with open(r'src\main\kotlin\routes\client-server\client\room\RoomMembershipRoutes.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# New redirect logic
new_code = '''            // If user has a pending invite, redirect to make_join flow
            // Per Matrix Spec v1.16: ALL federation joins (including invite acceptance) must use make_join
            println("JOIN: Checking federation join conditions - currentMembership=$currentMembership, inviteSender=$inviteSender")
            val effectiveServerNames = if (currentMembership == "invite" && inviteSender != null) {
                val inviterServer = inviteSender.substringAfter(":")
                println("JOIN: User has invite from $inviteSender - will use make_join via $inviterServer")
                listOf(inviterServer)
            } else {
                serverNames
            }
            
            // For local joins (no invite), check if room exists'''

# Pattern to match from 'If user has a pending invite' to 'For local joins'
pattern = r'            // If user has a pending invite, handle federated join\n.*?            // For local joins \(no invite\), check if room exists'

# Replace
new_content = re.sub(pattern, new_code, content, flags=re.DOTALL)

# Write back
with open(r'src\main\kotlin\routes\client-server\client\room\RoomMembershipRoutes.kt', 'w', encoding='utf-8') as f:
    f.write(new_content)
    
print('Replacement complete - removed', content.count('val inviterServer = inviteSender.substringAfter') - new_content.count('val inviterServer = inviteSender.substringAfter'), 'old inviterServer references')
