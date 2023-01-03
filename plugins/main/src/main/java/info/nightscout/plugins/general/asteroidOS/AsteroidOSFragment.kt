package info.nightscout.plugins.general.asteroidOS

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.android.support.DaggerFragment
import info.nightscout.plugins.databinding.AsteroidosFragmentBinding
import javax.inject.Inject

class AsteroidOSFragment : DaggerFragment() {
    @Inject lateinit var asteroidOSPlugin: AsteroidOSPlugin

    // NOTE: The binding is automatically named "AsteroidosFragmentBinding"
    // instead of "AsteroidOSFragmentBinding".
    private var _binding: AsteroidosFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        AsteroidosFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.asteroidosSendDataButton.setOnClickListener {
            asteroidOSPlugin.sendCurrentBGData()
        }
    }
}