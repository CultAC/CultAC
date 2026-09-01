package ac.grim.grimac.utils.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@RequiredArgsConstructor
@Setter
@Getter
public class KeepAliveData {

    private final long id;
    private final long timeSent;

    @Setter private long timeReceived;

    @Setter private long transReceived;


}
